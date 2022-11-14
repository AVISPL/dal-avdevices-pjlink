/*
 * Copyright (c) 2022 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.device.pjlink;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.AdvancedControllableProperty;
import com.avispl.symphony.api.dal.dto.control.ConnectionState;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.dal.communicator.SocketCommunicator;
import com.avispl.symphony.dal.util.StringUtils;
import com.google.common.collect.HashBiMap;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections.CollectionUtils;

import javax.security.auth.login.FailedLoginException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import static com.avispl.symphony.dal.util.ControllablePropertyFactory.*;
import static com.avispl.symphony.dal.util.PropertyUtils.normalizeUptime;

/**
 * Generic PJLink adapter.
 * Supported features:
 * Class 1/2
 * - Power Status Monitoring and Control
 * - Audio/Video Mute Monitoring and Control
 * - Error Status Monitoring (Lamp, Fans, Temperature, Cover, Filter, Other)
 * - Lamp Status Monitoring
 * - Projector/Display Name Monitoring
 * - Manufacturer Information
 * - Product Name
 * - Other General Device Information
 * - PJLink Class Version
 *
 * Class 2:
 * - Serial Number
 * - Input Source Monitoring and Control
 * - Software Version
 * - Input Terminal
 * - Input Resolution
 * - Recommended Resolution
 * - Filter Usage Time
 * - Lamp Replacement Model
 * - Filter Replacement Model
 * - Speaker Volume Control
 * - Microphone Volume Control
 * - Freeze Status Monitoring and Control
 *
 * @author Maksym.Rossiytsev/AVISPL Team
 */
public class PJLinkCommunicator extends SocketCommunicator implements Monitorable, Controller {
    /**
     * Some PJLink devices has a very narrow idle connection timeout set by default, larger than the default
     * CPX polling cycle interval. In order to avoid getting connection interrupted because of the timeout -
     * this process provides a way to send commands and keep the connection running, optional.
     * This functionality is enabled by the {@link #connectionKeepAliveTimeout} parameter.
     * */
    class PJLinkSessionKeeper implements Runnable {
        private volatile boolean inProgress;
        public PJLinkSessionKeeper() {
            inProgress = true;
        }

        @Override
        public void run() {
            while (inProgress) {
                try {
                TimeUnit.MILLISECONDS.sleep(1000);
                } catch (InterruptedException e) {
                    // Ignore for now
                }
                if (!inProgress || !isInitialized()) {
                    break;
                }
                updateAdapterStatus();
                if (devicePaused) {
                    continue;
                }
                if ((System.currentTimeMillis() - lastCommandTimestamp) > connectionKeepAliveTimeout) {
                    try {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Sending session refresh command.");
                        }
                        sendCommandWithRetry(PJLinkCommand.CLSS_STAT.getValue(), PJLinkCommand.CLSS_STAT.getResponseTemplate());
                    } catch (Exception e) {
                        logger.error("Unable to refresh the TCP session.", e);
                    } finally {
                        lastCommandTimestamp = System.currentTimeMillis();
                    }
                }
            }
        }

        /**
         * Triggers the loop to stop
         */
        public void stop() {
            inProgress = false;
        }
    }

    /**
     * Executor service, responsible for running {@link #pjLinkSessionKeeper} process
     * */
    private static ExecutorService executorService;

    /**
     * Internal process that updates the TCP session and keeps it alive
     * */
    private PJLinkSessionKeeper pjLinkSessionKeeper;

    /**
     * Adapter metadata, collected from the version.properties
     */
    private Properties adapterProperties;

    /**
     * Device adapter instantiation timestamp.
     */
    private long adapterInitializationTimestamp;

    /**
     * PJLink class version to use outside of the main loop
     * */
    private PJLinkConstants.PJLinkClass pjLinkClass;

    /**
     * Timestamp of the latest command issued
     * */
    private volatile long lastCommandTimestamp;

    /**
     * Inactivity timeout, for which session refresh should be granted. This parameter
     * guarantees that the session is not being idle for longer than the value set, measured in milliseconds.
     * */
    private volatile long connectionKeepAliveTimeout = 25000;

    /**
     * A default delay to apply in between of all the commands performed by the adapter.
     * */
    private long commandsCooldownDelay = 200;

    /**
     * If control commands should be delayed until monitoring cycle is finished
     * */
    private boolean delayControlCommands;

    /**
     * Whenever a PJLink requires authentication - it will reply with a random number, upon connection.
     * This is the random number to use withing the next command issued after the connection
     * */
    private String authenticationSuffix;

    /**
     * Indicates whether a device is considered as paused.
     * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
     * collection unless the {@link PJLinkCommunicator#getMultipleStatistics()} ()} method is called which will change it
     * to a correct value
     */
    private volatile boolean devicePaused = true;

    /**
     * This parameter holds timestamp of when we need to stop performing API calls
     * It used when device stop retrieving statistic. Updated each time of called {@link PJLinkCommunicator#getMultipleStatistics()}
     */
    private volatile long validRetrieveStatisticsTimestamp;

    /**
     * Aggregator inactivity timeout. If the {@link PJLinkCommunicator#getMultipleStatistics()} ()}  method is not
     * called during this period of time - device is considered to be paused, thus the TCP session shouldn't be kept alive
     */
    private static final long retrieveStatisticsTimeOut = 3 * 60 * 1000;

    /**
     * Timestamp of the last control operation, used to determine whether we need to wait
     * for {@link #CONTROL_OPERATION_COOLDOWN_MS} before collecting new statistics
     */
    private long latestControlTimestamp;

    /**
     * {@link #inputOptions} requires multiple commands execution, so in order to not overload the TCP socket connection
     * with too many commands, this property keeps timestamp of a latest optionsList state retrieval.
     * Delay is defined in {@link #inputOptionsRetrievalDelay} property.
     * */
    private long inputOptionsRetrievalTimestamp;

    /**
     * Defines delay for inputOptions retrieval:
     * {@link #inputOptions} requires multiple commands execution, so in order to not overload the TCP socket connection
     * with too many commands, this property keeps timestamp of a latest optionsList state retrieval.
     * 30m is the default value, can be changed through the adapter configuration.
     * */
    private long inputOptionsRetrievalDelay = 1000 * 60 * 30;

    /**
     * Indicator for the necessity of providing the initial commands validation for commands
     * that do not have read operations (such as mic and speaker volume change)
     * */
    private boolean initialControlsValidationFinished = false;

    private static final int CONTROL_OPERATION_COOLDOWN_MS = 5000;
    private static final int COMMAND_RETRY_ATTEMPTS = 10;
    private static final int COMMAND_RETRY_INTERVAL_MS = 200;

    private ExtendedStatistics localStatistics;
    private HashBiMap<String, String> inputOptions = HashBiMap.create();
    private List<String> unsupportedCommands = new ArrayList<>();
    private ReentrantLock tcpCommandsLock = new ReentrantLock();

    /**
     * Retrieves {@link #inputOptionsRetrievalDelay}
     *
     * @return value of {@link #inputOptionsRetrievalDelay}
     */
    public long getInputOptionsRetrievalDelay() {
        return inputOptionsRetrievalDelay;
    }

    /**
     * Sets {@link #inputOptionsRetrievalDelay} value
     *
     * @param inputOptionsRetrievalDelay new value of {@link #inputOptionsRetrievalDelay}
     */
    public void setInputOptionsRetrievalDelay(long inputOptionsRetrievalDelay) {
        this.inputOptionsRetrievalDelay = inputOptionsRetrievalDelay;
    }

    /**
     * Retrieves {@link #commandsCooldownDelay}
     *
     * @return value of {@link #commandsCooldownDelay}
     */
    public long getCommandsCooldownDelay() {
        return commandsCooldownDelay;
    }

    /**
     * Sets {@link #commandsCooldownDelay} value. Must not be less than 200ms
     *
     * @param commandsCooldownDelay new value of {@link #commandsCooldownDelay}
     */
    public void setCommandsCooldownDelay(long commandsCooldownDelay) {
        this.commandsCooldownDelay = Math.max(200, commandsCooldownDelay);
    }

    /**
     * Retrieves {@link #connectionKeepAliveTimeout}
     *
     * @return value of {@link #connectionKeepAliveTimeout}
     */
    public long getConnectionKeepAliveTimeout() {
        return connectionKeepAliveTimeout;
    }

    /**
     * Sets {@link #connectionKeepAliveTimeout} value
     *
     * @param connectionKeepAliveTimeout new value of {@link #connectionKeepAliveTimeout}
     */
    public void setConnectionKeepAliveTimeout(long connectionKeepAliveTimeout) {
        this.connectionKeepAliveTimeout = connectionKeepAliveTimeout;
    }

    /**
     * Retrieves {@link #delayControlCommands}
     *
     * @return value of {@link #delayControlCommands}
     */
    public boolean isDelayControlCommands() {
        return delayControlCommands;
    }

    /**
     * Sets {@link #delayControlCommands} value
     *
     * @param delayControlCommands new value of {@link #delayControlCommands}
     */
    public void setDelayControlCommands(boolean delayControlCommands) {
        this.delayControlCommands = delayControlCommands;
    }

    @Override
    protected void internalInit() throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Internal init is called.");
        }
        setCommandSuccessList(Collections.singletonList("="));
        setCommandErrorList(Arrays.asList(PJLinkConstants.UNDEFINED_COMMAND, PJLinkConstants.OUT_OF_PARAMETER, PJLinkConstants.UNAVAILABLE_TIME, PJLinkConstants.DEVICE_FAILURE, PJLinkConstants.PJLINK_ERRA));

        adapterInitializationTimestamp = System.currentTimeMillis();
        try {
            loadAdapterMetaData();
        } catch (IOException exc) {
            // Catching an error there because adapter should remain functional regardless of this issue.
            logger.error("Unable to load adapter metadata during internalInit stage.", exc);
        }

        if (connectionKeepAliveTimeout > 0) {
            executorService = Executors.newFixedThreadPool(1);

            lastCommandTimestamp = System.currentTimeMillis();
            pjLinkSessionKeeper = new PJLinkSessionKeeper();
            executorService.submit(pjLinkSessionKeeper);
        }
        super.internalInit();
    }

    @Override
    protected void internalDestroy() {
        adapterProperties = null;
        inputOptions.clear();
        unsupportedCommands.clear();
        if (pjLinkSessionKeeper != null) {
            pjLinkSessionKeeper.stop();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        super.internalDestroy();
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String propertyName = controllableProperty.getProperty();
        String propertyValue = String.valueOf(controllableProperty.getValue());

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Calling controlProperty for property %s with value %s", propertyName, propertyValue));
        }
        tcpCommandsLock.lock();
        try {
            String commandResponse = "";
            updateLatestControlTimestamp();
            byte[] command;
            switch (propertyName) {
                case PJLinkConstants.POWER_PROPERTY:
                    command = PJLinkCommand.POWR_CMD.getValue().clone();
                    command[7] = (byte) ("1".equals(propertyValue) ? 0x31 : 0x30);
                    commandResponse = sendCommandWithRetry(command, PJLinkCommand.POWR_CMD.getResponseTemplate());
                    validateControllableProperty(commandResponse, propertyName, propertyValue, true);
                    break;
                case PJLinkConstants.FREEZE_PROPERTY:
                    command = PJLinkCommand.FREZ_CMD.getValue().clone();
                    command[7] = (byte) ("1".equals(propertyValue) ? 0x31 : 0x30);
                    commandResponse = sendCommandWithRetry(command, PJLinkCommand.FREZ_CMD.getResponseTemplate());
                    validateControllableProperty(commandResponse, propertyName, propertyValue, true);
                    break;
                case PJLinkConstants.INPUT_PROPERTY:
                    command = PJLinkCommand.INPT_CMD.getValue().clone();
                    String inputCode = inputOptions.get(propertyValue);
                    if (StringUtils.isNullOrEmpty(inputCode)) {
                        throw new IllegalStateException(String.format("Unable to switch Input to %s mode: no input code available", inputOptions));
                    }
                    byte[] inputCodeBytes = stringToHex(inputOptions.get(propertyValue));
                    command[7] = inputCodeBytes[0]; // first byte of propertyValue
                    command[8] = inputCodeBytes[1]; // second byte of propertyValue
                    commandResponse = sendCommandWithRetry(command, PJLinkCommand.INPT_CMD.getResponseTemplate());
                    validateControllableProperty(commandResponse, propertyName, propertyValue, true);
                    break;
                case PJLinkConstants.VIDEOMUTE_PROPERTY:
                    command = PJLinkCommand.VIDEO_MUTE_CMD.getValue().clone();
                    command[8] = (byte) ("1".equals(propertyValue) ? 0x31 : 0x30);
                    commandResponse = sendCommandWithRetry(command, PJLinkCommand.VIDEO_MUTE_CMD.getResponseTemplate());
                    validateControllableProperty(commandResponse, propertyName, propertyValue, true);
                    break;
                case PJLinkConstants.AUDIOMUTE_PROPERTY:
                    command = PJLinkCommand.AUDIO_MUTE_CMD.getValue().clone();
                    command[8] = (byte) ("1".equals(propertyValue) ? 0x31 : 0x30);
                    commandResponse = sendCommandWithRetry(command, PJLinkCommand.AUDIO_MUTE_CMD.getResponseTemplate());
                    validateControllableProperty(commandResponse, propertyName, propertyValue, true);
                    break;
                case PJLinkConstants.MICROPHONE_VOLUME_UP:
                    command = PJLinkCommand.MVOL_CMD.getValue().clone();
                    command[7] = 0x31;
                    commandResponse = sendCommandWithRetry(command, PJLinkCommand.MVOL_CMD.getResponseTemplate());
                    validateControllableProperty(commandResponse, propertyName, propertyValue, false);
                    break;
                case PJLinkConstants.MICROPHONE_VOLUME_DOWN:
                    command = PJLinkCommand.MVOL_CMD.getValue().clone();
                    command[7] = 0x30;
                    commandResponse = sendCommandWithRetry(command, PJLinkCommand.MVOL_CMD.getResponseTemplate());
                    validateControllableProperty(commandResponse, propertyName, propertyValue, false);
                    break;
                case PJLinkConstants.SPEAKER_VOLUME_UP:
                    command = PJLinkCommand.SVOL_CMD.getValue().clone();
                    command[7] = 0x31;
                    commandResponse = sendCommandWithRetry(command, PJLinkCommand.SVOL_CMD.getResponseTemplate());
                    validateControllableProperty(commandResponse, propertyName, propertyValue, false);
                    break;
                case PJLinkConstants.SPEAKER_VOLUME_DOWN:
                    command = PJLinkCommand.SVOL_CMD.getValue().clone();
                    command[7] = 0x30;
                    commandResponse = sendCommandWithRetry(command, PJLinkCommand.SVOL_CMD.getResponseTemplate());
                    validateControllableProperty(commandResponse, propertyName, propertyValue, false);
                    break;
                default:
                    if (logger.isWarnEnabled()) {
                        logger.warn("");
                    }
                    break;
            }

        } finally {
            tcpCommandsLock.unlock();
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> list) throws Exception {
        if (CollectionUtils.isEmpty(list)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }
        for (ControllableProperty controllableProperty : list) {
            controlProperty(controllableProperty);
        }
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws Exception {
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();
        Map<String, String> statistics = new HashMap<>();
        List<AdvancedControllableProperty> advancedControllableProperties = new ArrayList<>();

        if (isValidControlCoolDown() && localStatistics != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Device is occupied. Skipping statistics refresh call.");
            }
            extendedStatistics.setStatistics(localStatistics.getStatistics());
            extendedStatistics.setControllableProperties(localStatistics.getControllableProperties());

            return Collections.singletonList(extendedStatistics);
        }

        tcpCommandsLock.lock();
        try {
            retrieveClassData(statistics);
            initialControlsValidation();
            if (pjLinkClass == PJLinkConstants.PJLinkClass.CLASS_1 || pjLinkClass == PJLinkConstants.PJLinkClass.CLASS_2) {
                String avmtResponse = sendCommandAndRetrieveValue(PJLinkCommand.AVMT_STAT);
                String erstResponse = sendCommandAndRetrieveValue(PJLinkCommand.ERST_STAT);
                String lampResponse = sendCommandAndRetrieveValue(PJLinkCommand.LAMP_STAT);
                String nameResponse = sendCommandAndRetrieveValue(PJLinkCommand.NAME_STAT);
                String inf1Response = sendCommandAndRetrieveValue(PJLinkCommand.INF1_STAT);
                String inf2Response = sendCommandAndRetrieveValue(PJLinkCommand.INF2_STAT);
                String infoResponse = sendCommandAndRetrieveValue(PJLinkCommand.INFO_STAT);

                populatePowerData(statistics, advancedControllableProperties);
                if (validateMonitorableProperty(avmtResponse)) {
                    populateAVMuteData(statistics, advancedControllableProperties, avmtResponse);
                }
                if (validateMonitorableProperty(erstResponse)) {
                    populateErrorStatusData(statistics, erstResponse);
                }
                if (validateMonitorableProperty(lampResponse)) {
                    populateLampData(statistics, lampResponse);
                }

                addStatisticsWithValidation(statistics, nameResponse, PJLinkConstants.DEVICE_NAME_PROPERTY, nameResponse, true);
                addStatisticsWithValidation(statistics, inf1Response, PJLinkConstants.MANUFACTURER_DETAILS_PROPERTY, inf1Response, true);
                addStatisticsWithValidation(statistics, inf2Response, PJLinkConstants.PRODUCT_DETAILS_PROPERTY, inf2Response, true);
                addStatisticsWithValidation(statistics, infoResponse, PJLinkConstants.DEVICE_DETAILS_PROPERTY, infoResponse, true);
                if (logger.isDebugEnabled()) {
                    logger.debug("Finished collecting Class 1 PJLink statistics");
                }
            }
            if (pjLinkClass == PJLinkConstants.PJLinkClass.CLASS_2) {
                String snumResponse = sendCommandAndRetrieveValue(PJLinkCommand.SNUM_STAT);
                String sverResponse = sendCommandAndRetrieveValue(PJLinkCommand.SVER_STAT);
                String filtResponse = sendCommandAndRetrieveValue(PJLinkCommand.FILT_STAT);
                String rfilResponse = sendCommandAndRetrieveValue(PJLinkCommand.RFIL_STAT);
                String rlmpResponse = sendCommandAndRetrieveValue(PJLinkCommand.RLMP_STAT);
                String rresResponse = sendCommandAndRetrieveValue(PJLinkCommand.RRES_STAT);
                String iresResponse = sendCommandAndRetrieveValue(PJLinkCommand.IRES_STAT);

                populateInputData(statistics, advancedControllableProperties);
                populateFreezeData(statistics, advancedControllableProperties);
                populateVolumeControls(statistics, advancedControllableProperties);

                addStatisticsWithValidation(statistics, snumResponse, PJLinkConstants.SERIAL_NUMBER_PROPERTY, snumResponse, true);
                addStatisticsWithValidation(statistics, sverResponse, PJLinkConstants.SOFTWARE_VERSION_PROPERTY, sverResponse, true);
                addStatisticsWithValidation(statistics, rresResponse, PJLinkConstants.RECOMMENDED_RESOLUTION_PROPERTY, rresResponse, true);
                addStatisticsWithValidation(statistics, iresResponse, PJLinkConstants.INPUT_RESOLUTION_PROPERTY, iresResponse, true);
                addStatisticsWithValidation(statistics, filtResponse, PJLinkConstants.FILTER_USAGE_PROPERTY, filtResponse, false);
                addStatisticsWithValidation(statistics, rfilResponse, PJLinkConstants.FILTER_REPLACEMENT_PROPERTY, rfilResponse, false);
                addStatisticsWithValidation(statistics, rlmpResponse, PJLinkConstants.LAMP_REPLACEMENT_PROPERTY, rlmpResponse, false);
                if (logger.isDebugEnabled()) {
                    logger.debug("Finished collecting Class 2 PJLink statistics");
                }
            }
            statistics.put(PJLinkConstants.METADATA_ADAPTER_VERSION_PROPERTY, adapterProperties.getProperty("adapter.version"));
            statistics.put(PJLinkConstants.METADATA_ADAPTER_BUILD_DATE_PROPERTY, adapterProperties.getProperty("adapter.build.date"));
            statistics.put(PJLinkConstants.METADATA_ADAPTER_UPTIME_PROPERTY, normalizeUptime(String.valueOf((System.currentTimeMillis() - adapterInitializationTimestamp) / 1000)));

            extendedStatistics.setStatistics(statistics);
            extendedStatistics.setControllableProperties(advancedControllableProperties);

            if (connectionKeepAliveTimeout > 0) {
                updateValidRetrieveStatisticsTimestamp();
            }

            localStatistics = extendedStatistics;
        } finally {
            tcpCommandsLock.unlock();
        }
        return Collections.singletonList(extendedStatistics);
    }

    /**
     * Add statistics property if is supported and not empty
     *
     * @param statistics to add property to
     * @param commandResponse command response to validate upon
     * @param propertyName name of the property to add
     * @param propertyValue value of the property to add
     * @param validateEmpty if empty values are valid or not
     * */
    private void addStatisticsWithValidation(Map<String, String> statistics, String commandResponse, String propertyName, String propertyValue, boolean validateEmpty){
        if ((validateEmpty || !StringUtils.isNullOrEmpty(commandResponse)) && validateMonitorableProperty(commandResponse)) {
            statistics.put(propertyName, propertyValue);
        }
    }

    @Override
    protected byte[] send(byte[] data) throws Exception {
        int commandSendAttempt = 0;
        tcpCommandsLock.lock();
        byte[] response;
        try {
            if (System.currentTimeMillis() - lastCommandTimestamp < commandsCooldownDelay) {
                Thread.sleep(commandsCooldownDelay);
            }
            if (logger.isTraceEnabled()) {
                logger.trace("Sending the PJLink command: " + PJLinkCommand.findByByteSequence(data));
            }
            lastCommandTimestamp = System.currentTimeMillis();
            response = super.send(data);
        } catch (SocketException sx) {
            logger.error("Socket communication exception occurred", sx);
            while (true) {
                commandSendAttempt++;
                if (commandSendAttempt > COMMAND_RETRY_ATTEMPTS) {
                    if (logger.isWarnEnabled()) {
                        logger.warn(String.format("Socket communication recovery attempts reached maximum value of %s. Unable to recover the socket communication.", COMMAND_RETRY_ATTEMPTS));
                    }
                    throw sx;
                }
                if (logger.isWarnEnabled()) {
                    logger.warn(String.format("Socket communication recovery attempt %s", commandSendAttempt), sx);
                }
                try {
                    lastCommandTimestamp = System.currentTimeMillis();
                    return super.send(data);
                } catch (SocketException nsx) {
                    logger.error(String.format("ERROR: Socket communication recovery attempt %s", commandSendAttempt), nsx);
                }
            }
        } finally {
            tcpCommandsLock.unlock();
        }
        return response;
    }

    /**
     * Check if the authentication is required and send the command, with or without the authentication.
     *
     * @param data command bytes
     * @return decoded {@link String} value of the command response bytes
     * @throws Exception if any error occurs
     * */
    private String sendCommand(byte[] data) throws Exception {
        if ((getConnectionStatus().getConnectionState() == ConnectionState.Disconnected ||
                getConnectionStatus().getConnectionState() == ConnectionState.Unknown) && data.length > 0) {
            String response =  sendCommandWithRetry(PJLinkCommand.BLANK.getValue(), "PJLINK");
            if (response.startsWith(PJLinkConstants.PJLINK_1) || response.startsWith(PJLinkConstants.PJLINK_ERRA)) {
                if (response.startsWith(PJLinkConstants.PJLINK_1)) {
                    authenticationSuffix = response.split("1")[1].trim();
                }
                return authorize(data);
            }
        }
        return new String(this.send(data));
    }

    /**
     * Send command with a specific response format expected.
     * Continue sending \n if the response does not match, within the {@link #COMMAND_RETRY_ATTEMPTS} attempts.
     *
     * @param command command to execute
     * @param expectedResponseTemplate expected response template
     * @return command response, String value
     *
     * @throws Exception if a communication error occurs
     * */
    private String sendCommandWithRetry(byte[] command, String expectedResponseTemplate) throws Exception {
        String response = sendCommand(command);
        if (response.equals(PJLinkConstants.UNDEFINED_COMMAND) || response.equals(PJLinkConstants.OUT_OF_PARAMETER)
                || response.equals(PJLinkConstants.UNAVAILABLE_TIME) || response.equals(PJLinkConstants.DEVICE_FAILURE) || response.contains(expectedResponseTemplate)) {
            return response;
        }

        int retryAttempts = 0;
        while (retryAttempts < COMMAND_RETRY_ATTEMPTS) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Retrieving value for command %s. Expected response template %s but received %s", new String(command), expectedResponseTemplate, response));
            }
            retryAttempts++;
            if (response.equals(PJLinkConstants.UNDEFINED_COMMAND) || response.equals(PJLinkConstants.OUT_OF_PARAMETER)
                    || response.equals(PJLinkConstants.UNAVAILABLE_TIME) || response.equals(PJLinkConstants.DEVICE_FAILURE) || response.contains(expectedResponseTemplate)) {
                return response;
            }
            TimeUnit.MILLISECONDS.sleep(COMMAND_RETRY_INTERVAL_MS);
            // "Scrolling" to the latest value, if the commands sequence was broken at some point.

            response = sendCommand(PJLinkCommand.BLANK.getValue());
        }

        return PJLinkConstants.N_A;
    }

    /**
     * Sends command and maps it to the correct and human-readable format, if needed
     *
     * @param command to issue
     * @return human-readable command response
     * @throws Exception if any error occurs
     * */
    private String sendCommandAndRetrieveValue(PJLinkCommand command) throws Exception {
        String commandName = command.name();
        if (unsupportedCommands.contains(commandName)) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Command %s is not supported, skipping", commandName));
            }
            return PJLinkConstants.UNDEFINED_COMMAND;
        }
        String responseValue = retrieveResponseValue(sendCommandWithRetry(command.getValue(), command.getResponseTemplate())).trim();

        if (PJLinkConstants.UNDEFINED_COMMAND.equals(responseValue)) {
            if (!unsupportedCommands.contains(commandName)) {
                unsupportedCommands.add(commandName);
            }
            if (logger.isWarnEnabled()) {
                logger.warn("Undefined Command: " + new String(command.getValue()));
            }
            return PJLinkConstants.UNDEFINED_COMMAND;
        } else if(PJLinkConstants.OUT_OF_PARAMETER.equals(responseValue)) {
            if (logger.isWarnEnabled()) {
                logger.warn("Out of parameter: " + new String(command.getValue()));
            }
            return PJLinkConstants.OUT_OF_PARAMETER;
        } else if(PJLinkConstants.UNAVAILABLE_TIME.equals(responseValue)) {
            if (logger.isWarnEnabled()) {
                logger.warn("Unavailable time: " + new String(command.getValue()));
            }
            return PJLinkConstants.UNAVAILABLE_TIME;
        } else if(PJLinkConstants.DEVICE_FAILURE.equals(responseValue)) {
            if (logger.isWarnEnabled()) {
                logger.warn("Projector/Display failure: " + new String(command.getValue()));
            }
            return PJLinkConstants.DEVICE_FAILURE;
        } else if("-".equals(responseValue)) {
            return PJLinkConstants.N_A;
        }
        return responseValue;
    }

    /**
     * Authorize using the command provided. Should be called once per session
     *
     * @param data command to use along with the authorization
     * @return String value result of the command execution
     *
     * @throws Exception if any error occurs
     * */
    private String authorize(byte[] data) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("An attempt to authorize with command: " + new String(data));
        }
        String digest = DigestUtils.md5Hex(String.format("%s%s", authenticationSuffix, password).getBytes());
        byte[] command = String.format("%s%s", digest, new String(data)).getBytes();
        String response = sendCommand(command);
        if (response.contains(PJLinkConstants.PJLINK_ERRA)) {
            throw new FailedLoginException("Unable to authorize, please check device password");
        }
        return response;
    }

    /**
     * Extracts the response value from the PJLink command response
     * e.g 'CLSS=1' would be changed to '1'
     *
     * @param response provided by PJLink device
     * @return String value of the PJLink command response, without the command feedback
     * */
    private String retrieveResponseValue(String response) {
        if (logger.isDebugEnabled()) {
            logger.debug("Received command response: " + response);
        }
        if (response.length() <= 1 || !response.contains("=")) {
            return "";
        }
        return response.substring(response.indexOf("=") + 1, response.indexOf("\r"));
    }

    /**
     * Issue 'CLSS?' command and retrieve its value. Used to avoid fetching unsupported commands.
     *
     * @param statistics to save class version property to
     * @throws Exception if a communication error occurs
     * */
    private void retrieveClassData(Map<String, String> statistics) throws Exception {
        String classResponse = retrieveResponseValue(sendCommandWithRetry(PJLinkCommand.CLSS_STAT.getValue(), PJLinkCommand.CLSS_STAT.getResponseTemplate()));
        if ("1".equals(classResponse)) {
            pjLinkClass = PJLinkConstants.PJLinkClass.CLASS_1;
        } else if ("2".equals(classResponse)){
            pjLinkClass = PJLinkConstants.PJLinkClass.CLASS_2;
        }
        statistics.put(PJLinkConstants.PJLINK_CLASS_PROPERTY, classResponse);
    }

    /**
     * Retrieve error status as a text (uppercase with underscores instead of spaces, to ease up threshold definition process).
     *
     * @param error being one of the following: ERR1, ERR2, ERR3, ERR4, ERRA
     * @return one of the following:
     *          {@link PJLinkConstants#UNDEFINED_COMMAND_TEXT}
     *          {@link PJLinkConstants#OUT_OF_PARAMETER_TEXT}
     *          {@link PJLinkConstants#UNAVAILABLE_TIME_TEXT}
     *          {@link PJLinkConstants#DEVICE_FAILURE_TEXT}
     *          {@link PJLinkConstants#ERRA_TEXT}
     * */
    private String retrieveErrorStatus(String error) {
        switch (error) {
            case PJLinkConstants.UNDEFINED_COMMAND:
                return PJLinkConstants.UNDEFINED_COMMAND_TEXT;
            case PJLinkConstants.OUT_OF_PARAMETER:
                return PJLinkConstants.OUT_OF_PARAMETER_TEXT;
            case PJLinkConstants.UNAVAILABLE_TIME:
                return PJLinkConstants.UNAVAILABLE_TIME_TEXT;
            case PJLinkConstants.DEVICE_FAILURE:
                return PJLinkConstants.DEVICE_FAILURE_TEXT;
            case PJLinkConstants.PJLINK_ERRA:
                return PJLinkConstants.ERRA_TEXT;
            default:
                return "NO ERROR";
        }
    }

    /**
     * Update input options once every {@link #inputOptionsRetrievalDelay}
     *
     * @throws Exception if any communication error occurs
     * */
    private void retrieveInputOptions() throws Exception {
        if (!inputOptions.isEmpty() && System.currentTimeMillis() < inputOptionsRetrievalTimestamp + inputOptionsRetrievalDelay) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Input options retrieval operation is on the timeout. Current input options: %s, last update timestamp: %s, next update timestamp: %s",
                        inputOptions, inputOptionsRetrievalTimestamp, System.currentTimeMillis()));
            }
            return;
        }
        byte[] inputNameCommand = PJLinkCommand.INNM_STAT.getValue().clone();
        String instResponse = sendCommandAndRetrieveValue(PJLinkCommand.INST_STAT_C2);
        if (StringUtils.isNullOrEmpty(instResponse)) {
            if (logger.isDebugEnabled()) {
                logger.debug("INST Response haven't provided any data, skipping input options retrieval.");
            }
            return;
        }
        String[] inputs = instResponse.split(" ");
        inputOptions.clear();
        for (String inputCode: inputs) {
            byte[] hexValues = stringToHex(inputCode);
            inputNameCommand[8] = hexValues[0];
            inputNameCommand[9] = hexValues[1];

            String inputName = retrieveResponseValue(sendCommandWithRetry(inputNameCommand, PJLinkCommand.INNM_STAT.getResponseTemplate()));
            inputOptions.put(inputName, inputCode);
        }

        inputOptionsRetrievalTimestamp = System.currentTimeMillis();
    }

    /**
     * Create power control with a proper status
     *
     * @param statistics to keep power property
     * @param controls to keep power control
     * @throws Exception if communication error occurs
     * */
    private void populatePowerData(Map<String, String> statistics, List<AdvancedControllableProperty> controls) throws Exception {
        String powrResponse = sendCommandAndRetrieveValue(PJLinkCommand.POWR_STAT);
        statistics.put(PJLinkConstants.POWER_PROPERTY, powrResponse);
        if (isNumeric(powrResponse)) {
            controls.add(createSwitch(PJLinkConstants.POWER_PROPERTY, Integer.parseInt(powrResponse)));
        }
    }

    /**
     * Create picture freeze toggle with a proper status
     *
     * @param statistics to keep freeze property
     * @param controls to keep freeze control
     * @throws Exception if communication error occurs
     * */
    private void populateFreezeData(Map<String, String> statistics, List<AdvancedControllableProperty> controls) throws Exception {
        String freezeResponse = sendCommandAndRetrieveValue(PJLinkCommand.FREZ_STAT);
        boolean isNumericResponse = isNumeric(freezeResponse);
        if (!StringUtils.isNullOrEmpty(freezeResponse) && validateMonitorableProperty(freezeResponse)) {
            if (isNumericResponse) {
                statistics.put(PJLinkConstants.FREEZE_PROPERTY, "1".equals(freezeResponse) ? PJLinkConstants.STATUS_ON : PJLinkConstants.STATUS_OFF);
            } else {
                statistics.put(PJLinkConstants.FREEZE_PROPERTY, retrieveErrorStatus(freezeResponse));
            }
            if ("1".equals(statistics.get(PJLinkConstants.POWER_PROPERTY)) && isNumericResponse) {
                controls.add(createSwitch(PJLinkConstants.FREEZE_PROPERTY, Integer.parseInt(freezeResponse)));
            }
        }
    }

    /**
     * Create mic and speaker controls
     *
     * @param statistics to keep volume properties
     * @param controls to keep volume controls
     * */
    private void populateVolumeControls(Map<String, String> statistics, List<AdvancedControllableProperty> controls) {
        generateVolumeControlsWithValidation(statistics, controls, PJLinkConstants.MICROPHONE_VOLUME_UP, PJLinkConstants.MICROPHONE_VOLUME_DOWN);
        generateVolumeControlsWithValidation(statistics, controls, PJLinkConstants.SPEAKER_VOLUME_UP, PJLinkConstants.SPEAKER_VOLUME_DOWN);
    }

    /**
     * Add generic volume up/down properties if supported
     *
     * @param statistics to keep volume properties
     * @param controls to keep volume controls
     * @param volumeUpParameter volume up parameter name
     * @param volumeDownParameter volume down parameter name
     * */
    private void generateVolumeControlsWithValidation(Map<String, String> statistics, List<AdvancedControllableProperty> controls, String volumeUpParameter, String volumeDownParameter) {
        if (!unsupportedCommands.contains(volumeUpParameter) && !unsupportedCommands.contains(volumeDownParameter)) {
            statistics.put(volumeUpParameter, "");
            controls.add(createButton(volumeUpParameter, "+", "+", 0L));
            statistics.put(volumeDownParameter, "");
            controls.add(createButton(volumeDownParameter, "-", "-", 0L));
        }
    }

    /**
     * PJLink does not provide ability to check support for certain commands, however,
     * unsupported properties/controls should not be displayed.
     * This method is supposed to be called once per adapter initialization.
     * It attempts to call command, effectively rolling back the command action, if needed.
     * Unsupported commands then are added to {@link #unsupportedCommands}
     * */
    private void initialControlsValidation () {
        if (initialControlsValidationFinished) {
            if (logger.isDebugEnabled()) {
                logger.debug("Initial controls validation is finished with the following list of unsupported commands: " + unsupportedCommands);
            }
            return;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Providing initial controls validation.");
        }
        try {
            byte[] micVolumeCmd = PJLinkCommand.MVOL_CMD.getValue().clone();
            micVolumeCmd[7] = 0x31;
            validateControllableProperty(sendCommandWithRetry(micVolumeCmd, "MVOL"), PJLinkConstants.MICROPHONE_VOLUME_UP, null, false);
            micVolumeCmd[7] = 0x30;
            sendCommandWithRetry(micVolumeCmd, "MVOL");
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Microphone volume command validation: microphone volume change command is not supported.");
            }
        }

        try {
            byte[] spVolumeCmd = PJLinkCommand.SVOL_CMD.getValue().clone();
            spVolumeCmd[7] = 0x31;
            validateControllableProperty(sendCommandWithRetry(spVolumeCmd, "SVOL"), PJLinkConstants.SPEAKER_VOLUME_UP, null, false);
            spVolumeCmd[7] = 0x30;
            sendCommandWithRetry(spVolumeCmd, "SVOL");
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Speaker volume command validation: speaker volume change command is not supported.");
            }
        }
        initialControlsValidationFinished = true;
    }

    /**
     * Create {@link PJLinkConstants#INPUT_PROPERTY} dropdown control based on a {@link #inputOptions} map, which is
     * retrieved once every {@link #inputOptionsRetrievalDelay}
     *
     * @param controls to put controls into
     * @param statistics to put statistics into
     * @throws Exception if any error occurs
     * */
    private void populateInputData(Map<String, String> statistics, List<AdvancedControllableProperty> controls) throws Exception {
        retrieveInputOptions();

        String inptResponse = sendCommandAndRetrieveValue(PJLinkCommand.INPT_STAT);
        if (!StringUtils.isNullOrEmpty(inptResponse) && validateMonitorableProperty(inptResponse)) {
            String dropdownValue = inputOptions.inverse().get(inptResponse);
            statistics.put(PJLinkConstants.INPUT_PROPERTY, dropdownValue);
            if ("1".equals(statistics.get(PJLinkConstants.POWER_PROPERTY))) {
                controls.add(createDropdown(PJLinkConstants.INPUT_PROPERTY, new ArrayList<>(inputOptions.keySet()), dropdownValue));
            }
        }
    }

    /**
     * Transform string value to hex, single character at the time
     *
     * @param arg string value to process
     * @return byte[] resulting byte sequence
     * */
    public byte[] stringToHex(String arg) {
        byte[] hexBytes = new byte[arg.length()];
        for (int i = 0; i < arg.length(); i++) {
            String nByte = String.format("%x",
                    new BigInteger(1, String.valueOf(arg.charAt(i)).getBytes(StandardCharsets.UTF_8)));
            hexBytes[i] = Integer.valueOf(nByte, 16).byteValue();
        }
        return hexBytes;
    }

    /**
     * Populate AVMute/AudioMute/VideoMute statistics and control property, based on the response value
     * 11 = video mute ON
     * 21 = audio mute ON
     * 31 = video AND audio mute ON
     * 30 = video AND audio mute OFF
     *
     * @param statistics to save statistics to
     * @param controls to save controls to
     * @param avMuteResponse avMute response value
     * */
    private void populateAVMuteData(Map<String, String> statistics, List<AdvancedControllableProperty> controls, String avMuteResponse) {
        switch (avMuteResponse) {
            case "30":
                createAVMuteControl(statistics, controls, PJLinkConstants.AUDIOMUTE_PROPERTY, "0");
                createAVMuteControl(statistics, controls, PJLinkConstants.VIDEOMUTE_PROPERTY, "0");
                break;
            case "31":
                createAVMuteControl(statistics, controls, PJLinkConstants.AUDIOMUTE_PROPERTY, "1");
                createAVMuteControl(statistics, controls, PJLinkConstants.VIDEOMUTE_PROPERTY, "1");
                break;
            case "21":
                createAVMuteControl(statistics, controls, PJLinkConstants.AUDIOMUTE_PROPERTY, "1");
                createAVMuteControl(statistics, controls, PJLinkConstants.VIDEOMUTE_PROPERTY, "0");
                break;
            case "11":
                createAVMuteControl(statistics, controls, PJLinkConstants.AUDIOMUTE_PROPERTY, "0");
                createAVMuteControl(statistics, controls, PJLinkConstants.VIDEOMUTE_PROPERTY, "1");
                break;
            default:
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("AVMute parameter for AVMT response %s is not implemented.", avMuteResponse));
                }
                break;
        }
    }

    /**
     * Create audio/video mute control
     * @param statistics map to keep property in
     * @param controls list to keep controllable property in
     * @param value current value of the property
     * */
    private void createAVMuteControl(Map<String, String> statistics, List<AdvancedControllableProperty> controls, String propertyName, String value) {
        boolean isNumericResponse = isNumeric(value);
        if (isNumericResponse) {
            statistics.put(propertyName, "1".equals(value) ? PJLinkConstants.STATUS_ON : PJLinkConstants.STATUS_OFF);
        } else {
            statistics.put(propertyName, retrieveErrorStatus(value));
        }
        if ("1".equals(statistics.get(PJLinkConstants.POWER_PROPERTY)) && isNumericResponse) {
            controls.add(createSwitch(propertyName, Integer.parseInt(value)));
        }
    }

    /**
     * Provide error status data
     * 0: No error detected or no error detecting function
     * 1: Warning
     * 2: Error
     *
     * @param statistics to save error status to
     * @param errstResponse error status response string, 6 digits, e.g '000100' -> cover warning
     */
    private void populateErrorStatusData(Map<String, String> statistics, String errstResponse){
        if (errstResponse.length() < 6) {
            return;
        }
        char fanError = errstResponse.charAt(0);
        char lampError = errstResponse.charAt(1);
        char temperatureError = errstResponse.charAt(2);
        char coverOpenError = errstResponse.charAt(3);
        char filterError = errstResponse.charAt(4);
        char otherError = errstResponse.charAt(5);

        statistics.put(PJLinkConstants.ERROR_FAN_PROPERTY, errorStatusString(fanError));
        statistics.put(PJLinkConstants.ERROR_LAMP_PROPERTY, errorStatusString(lampError));
        statistics.put(PJLinkConstants.ERROR_TEMPERATURE_PROPERTY, errorStatusString(temperatureError));
        statistics.put(PJLinkConstants.ERROR_COVER_PROPERTY, errorStatusString(coverOpenError));
        statistics.put(PJLinkConstants.ERROR_FILTER_PROPERTY, errorStatusString(filterError));
        statistics.put(PJLinkConstants.ERROR_OTHER_PROPERTY, errorStatusString(otherError));
    }

    /**
     * Provides value for error status property
     *
     * @param id positional digit extracted from the PJLink ERRST response
     * @return human-readable definition of an error status
     * */
    private String errorStatusString(char id) {
        if (id == '0') {
            return PJLinkConstants.STATUS_OK;
        } else if (id == '1') {
            return PJLinkConstants.STATUS_WARNING;
        } else if (id == '2') {
            return PJLinkConstants.STATUS_ERROR;
        }
        return PJLinkConstants.N_A;
    }

    /**
     * Provides lamp data, if available
     *
     * @param statistics to save statistics data to
     * @param lampResponse PJLink response
     * */
    private void populateLampData(Map<String, String> statistics, String lampResponse) {
        if (StringUtils.isNullOrEmpty(lampResponse)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to retrieve Lamp data. Please check device ErrorStatus group and WARN level logs for details.");
            }
            return;
        }
        String[] lampsData = lampResponse.split(" ");
        int lampIndex = 0;
        for (int i = 0; i < lampsData.length; i++) {
            if (i % 2 == 0) {
                lampIndex++;
                statistics.put(String.format("Lamp#Lamp%sUsageTime", lampIndex), lampsData[i]);
            } else {
                statistics.put(String.format("Lamp#Lamp%sStatus", lampIndex), "1".equals(lampsData[i]) ? PJLinkConstants.STATUS_ON : PJLinkConstants.STATUS_OFF);
            }
        }

    }

    /**
     * Load adapter metadata - adapter.version, adapter.build.date and adapter.uptime, based on
     * the build data and {@link #adapterInitializationTimestamp}
     *
     * @throws IOException if unable to read "version.properties" file
     */
    private void loadAdapterMetaData() throws IOException {
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
    }

    /**
     * Validate monitorable property and return false if the property is not supported
     *
     * @param commandResponseValue command response value to validate upon
     * @return boolean, true if property is valid, false if not valid
     * */
    private boolean validateMonitorableProperty(String commandResponseValue) {
        return !PJLinkConstants.UNDEFINED_COMMAND.equals(commandResponseValue);
    }
    /**
     * Update local statistics state, to provide when emergency delivery cycle is supposed to be skipped
     *
     * @param name property name
     * @param value property value
     * @param commandResponseValue command response
     * @param updateValue for whether cached controllable property should be updated or not
     * */
    private void validateControllableProperty(String commandResponseValue, String name, String value, boolean updateValue) {
        switch(retrieveResponseValue(commandResponseValue)) {
            case PJLinkConstants.UNDEFINED_COMMAND:
                if (!unsupportedCommands.contains(name)) {
                    unsupportedCommands.add(name);
                }
                throw new IllegalArgumentException("Unsupported control command: " + name);
            case PJLinkConstants.OUT_OF_PARAMETER:
                throw new IllegalArgumentException("Missing control command parameter: " + name);
            case PJLinkConstants.UNAVAILABLE_TIME:
                throw new IllegalStateException("Unable to send control command due to the device state");
            case PJLinkConstants.DEVICE_FAILURE:
                throw new RuntimeException("Unable to send control command due to the general device failure");
            default:
                if (logger.isDebugEnabled()) {
                    logger.debug("Finished processing control command: " + name);
                }
                break;
        }
        unsupportedCommands.remove(name);
        if (localStatistics != null && updateValue) {
            localStatistics.getControllableProperties().stream().filter(advancedControllableProperty -> name.equals(advancedControllableProperty.getName())).forEach(advancedControllableProperty -> {
                advancedControllableProperty.setValue(value);
            });
            localStatistics.getStatistics().put(name, value);
            if (PJLinkConstants.POWER_PROPERTY.equals(name) && "0".equals(value)) {
                localStatistics.getControllableProperties().removeIf(advancedControllableProperty -> {
                    String propertyName = advancedControllableProperty.getName();
                    return PJLinkConstants.INPUT_PROPERTY.equals(propertyName) || PJLinkConstants.AUDIOMUTE_PROPERTY.equals(propertyName) ||
                            PJLinkConstants.VIDEOMUTE_PROPERTY.equals(propertyName) || PJLinkConstants.FREEZE_PROPERTY.equals(propertyName);
                });
            }
        }
    }

    /**
     * Update the status of the device.
     * The device is considered as paused if did not receive any getMultipleStatistics()
     * calls during {@link PJLinkCommunicator#validRetrieveStatisticsTimestamp}
     */
    private synchronized void updateAdapterStatus() {
        boolean newPausedState = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
        if (!devicePaused && newPausedState) {
            try {
                disconnect();
            } catch (Exception e) {
                logger.error("Unable to disconnect the TCP session on device pause.", e);
            }
        }
        devicePaused = newPausedState;
    }

    /**
     * Update general aggregator status (paused or active) and update the value, based on which
     * it the device is considered paused (2 minutes inactivity -> {@link #retrieveStatisticsTimeOut})
     */
    private synchronized void updateValidRetrieveStatisticsTimestamp() {
        validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
        updateAdapterStatus();
    }

    /**
     * Update timestamp of the latest control operation
     */
    private void updateLatestControlTimestamp() {
        latestControlTimestamp = System.currentTimeMillis();
    }

    /***
     * Check whether the control operations cooldown has ended
     *
     * @return boolean value indicating whether the cooldown has ended or not
     */
    private boolean isValidControlCoolDown() {
        return (System.currentTimeMillis() - latestControlTimestamp) < CONTROL_OPERATION_COOLDOWN_MS;
    }

    /**
     * Checks if the sting contains numeric values
     *
     * @param string to check
     * @return boolean value, true if numeric, false otherwise
     * */
    public static boolean isNumeric(String string) {
        if (string == null) {
            return false;
        }
        try {
            Double.parseDouble(string);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }
}
