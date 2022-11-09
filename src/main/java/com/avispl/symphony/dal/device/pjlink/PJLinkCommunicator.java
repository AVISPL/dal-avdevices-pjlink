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

import static com.avispl.symphony.dal.device.pjlink.PJLinkCommand.*;
import static com.avispl.symphony.dal.device.pjlink.PJLinkConstants.*;
import static com.avispl.symphony.dal.device.pjlink.PJLinkConstants.PJLinkClass.CLASS_1;
import static com.avispl.symphony.dal.device.pjlink.PJLinkConstants.PJLinkClass.CLASS_2;
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
                        sendCommandWithRetry(CLSS_STAT.getValue(), CLSS_STAT.getResponseTemplate());
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
    private long commandsCooldownDelay = 500;

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
     * Indicator to keep track of the authentication status. Authentication command is required once per
     * communication session. Once authenticated - this property changes to false.
     * */
    private boolean authenticationRequired = false;

    private static final int CONTROL_OPERATION_COOLDOWN_MS = 5000;
    private static final int COMMAND_RETRY_ATTEMPTS = 10;
    private static final int COMMAND_RETRY_INTERVAL_MS = 200;

    private ExtendedStatistics localStatistics;
    private HashBiMap<String, String> inputOptions = HashBiMap.create();
    private ConcurrentMap<String, String> unsupportedCommands = new ConcurrentHashMap<>();
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
        setCommandErrorList(Arrays.asList("ERR1", "ERR2", "ERR3", "ERR4", "ERRA"));

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
            updateLatestControlTimestamp();
            byte[] command;
            switch (propertyName) {
                case POWER_PROPERTY:
                    command = POWR_CMD.getValue().clone();
                    command[7] = (byte) ("1".equals(propertyValue) ? 0x31 : 0x30);
                    sendCommandWithRetry(command, "POWR");
                    updateLocalControllableProperty(propertyName, propertyValue);
                    break;
                case INPUT_PROPERTY:
                    command = INPT_CMD.getValue().clone();
                    String inputCode = inputOptions.get(propertyValue);
                    if (StringUtils.isNullOrEmpty(inputCode)) {
                        throw new IllegalStateException(String.format("Unable to switch Input to %s mode: no input code available", inputOptions));
                    }
                    byte[] inputCodeBytes = stringToHex(inputOptions.get(propertyValue));
                    command[7] = inputCodeBytes[0]; // first byte of propertyValue
                    command[8] = inputCodeBytes[1]; // second byte of propertyValue
                    sendCommandWithRetry(command, "INPT");
                    updateLocalControllableProperty(propertyName, propertyValue);
                    break;
                case VIDEOMUTE_PROPERTY:
                    command = VIDEO_MUTE_CMD.getValue().clone();
                    command[8] = (byte) ("1".equals(propertyValue) ? 0x31 : 0x30);
                    sendCommandWithRetry(command, "AVMT");
                    updateLocalControllableProperty(propertyName, propertyValue);
                    break;
                case AUDIOMUTE_PROPERTY:
                    command = AUDIO_MUTE_CMD.getValue().clone();
                    command[8] = (byte) ("1".equals(propertyValue) ? 0x31 : 0x30);
                    sendCommandWithRetry(command, "AVMT");
                    updateLocalControllableProperty(propertyName, propertyValue);
                    break;
                case MICROPHONE_VOLUME_UP:
                    command = MVOL_CMD.getValue().clone();
                    command[7] = 0x31;
                    sendCommandWithRetry(command, "MVOL");
                    break;
                case MICROPHONE_VOLUME_DOWN:
                    command = MVOL_CMD.getValue().clone();
                    command[7] = 0x30;
                    sendCommandWithRetry(command, "MVOL");
                    break;
                case SPEAKER_VOLUME_UP:
                    command = SVOL_CMD.getValue().clone();
                    command[7] = 0x31;
                    sendCommandWithRetry(command, "SVOL");
                    break;
                case SPEAKER_VOLUME_DOWN:
                    command = SVOL_CMD.getValue().clone();
                    command[7] = 0x30;
                    sendCommandWithRetry(command, "SVOL");
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

        retrieveClassData(statistics);
        if (pjLinkClass == CLASS_1 || pjLinkClass == CLASS_2) {
            String avmtResponse = sendCommandAndRetrieveValue(PJLinkCommand.AVMT_STAT);
            String erstResponse = sendCommandAndRetrieveValue(PJLinkCommand.ERST_STAT);
            String lampResponse = sendCommandAndRetrieveValue(PJLinkCommand.LAMP_STAT);
            String nameResponse = sendCommandAndRetrieveValue(PJLinkCommand.NAME_STAT);
            String inf1Response = sendCommandAndRetrieveValue(PJLinkCommand.INF1_STAT);
            String inf2Response = sendCommandAndRetrieveValue(PJLinkCommand.INF2_STAT);
            String infoResponse = sendCommandAndRetrieveValue(PJLinkCommand.INFO_STAT);

            populatePowerData(statistics, advancedControllableProperties);

            populateAVMuteData(statistics, advancedControllableProperties, avmtResponse);
            populateErrorStatusData(statistics, erstResponse);
            populateLampData(statistics, lampResponse);

            statistics.put(DEVICE_NAME_PROPERTY, nameResponse);
            statistics.put(MANUFACTURER_DETAILS_PROPERTY, inf1Response);
            statistics.put(PRODUCT_DETAILS_PROPERTY, inf2Response);
            statistics.put(DEVICE_DETAILS_PROPERTY, infoResponse);
            if (logger.isDebugEnabled()) {
                logger.debug("Finished collecting Class 1 PJLink statistics");
            }
        }
        if (pjLinkClass == CLASS_2) {
            String snumResponse = sendCommandAndRetrieveValue(PJLinkCommand.SNUM_STAT);
            statistics.put(SERIAL_NUMBER_PROPERTY, snumResponse);
            String sverResponse = sendCommandAndRetrieveValue(PJLinkCommand.SVER_STAT);
            statistics.put(SOFTWARE_VERSION_PROPERTY, sverResponse);

            String filtResponse = sendCommandAndRetrieveValue(PJLinkCommand.FILT_STAT);
            if (!StringUtils.isNullOrEmpty(filtResponse)) {
                statistics.put(FILTER_USAGE_PROPERTY, filtResponse);
            }
            String rfilResponse = sendCommandAndRetrieveValue(PJLinkCommand.RFIL_STAT);
            if (!StringUtils.isNullOrEmpty(rfilResponse)) {
                statistics.put(FILTER_REPLACEMENT_PROPERTY, rfilResponse);
            }
            String rlmpResponse = sendCommandAndRetrieveValue(PJLinkCommand.RLMP_STAT);
            if (!StringUtils.isNullOrEmpty(rlmpResponse)) {
                statistics.put(LAMP_REPLACEMENT_PROPERTY, rlmpResponse);
            }

            populateInputData(statistics, advancedControllableProperties);
            populateFreezeData(statistics, advancedControllableProperties);
            populateVolumeControls(statistics, advancedControllableProperties);

            String rresResponse = sendCommandAndRetrieveValue(PJLinkCommand.RRES_STAT);
            statistics.put(RECOMMENDED_RESOLUTION_PROPERTY, rresResponse);
            String iresResponse = sendCommandAndRetrieveValue(PJLinkCommand.IRES_STAT);
            statistics.put(INPUT_RESOLUTION_PROPERTY, iresResponse);
            if (logger.isDebugEnabled()) {
                logger.debug("Finished collecting Class 2 PJLink statistics");
            }
        }
        statistics.put(METADATA_ADAPTER_VERSION_PROPERTY, adapterProperties.getProperty("adapter.version"));
        statistics.put(METADATA_ADAPTER_BUILD_DATE_PROPERTY, adapterProperties.getProperty("adapter.build.date"));
        statistics.put(METADATA_ADAPTER_UPTIME_PROPERTY, normalizeUptime(String.valueOf((System.currentTimeMillis() - adapterInitializationTimestamp) / 1000)));

        extendedStatistics.setStatistics(statistics);
        extendedStatistics.setControllableProperties(advancedControllableProperties);

        if(connectionKeepAliveTimeout > 0) {
            updateValidRetrieveStatisticsTimestamp();
        }

        localStatistics = extendedStatistics;
        return Collections.singletonList(extendedStatistics);
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
            if (response.startsWith(PJLinkConstants.PJLINK_1) || response.startsWith(PJLINK_ERRA)) {
                authenticationRequired = true;
                if (response.startsWith(PJLinkConstants.PJLINK_1)) {
                    authenticationSuffix = response.split("1")[1].trim();
                }
                return authorize(data);
            } else if (response.startsWith(PJLinkConstants.PJLINK_0)) {
                authenticationRequired = false;
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
        if (response.equals("ERR1") || response.equals("ERR2")
                || response.equals("ERR3") || response.equals("ERR4") || response.contains(expectedResponseTemplate)) {
            return response;
        }

        int retryAttempts = 0;
        while (retryAttempts < COMMAND_RETRY_ATTEMPTS) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Retrieving value for command %s. Expected response template %s but received %s", new String(command), expectedResponseTemplate, response));
            }
            retryAttempts++;
            if (response.equals("ERR1") || response.equals("ERR2")
                    || response.equals("ERR3") || response.equals("ERR4") || response.contains(expectedResponseTemplate)) {
                return response;
            }
            TimeUnit.MILLISECONDS.sleep(COMMAND_RETRY_INTERVAL_MS);
            // "Scrolling" to the latest value, if the commands sequence was broken at some point.

            response = sendCommand(PJLinkCommand.BLANK.getValue());
        }

        return "N/A";
    }

    /**
     * Sends command and maps it to the correct and human-readable format, if needed
     *
     * @param command to issue
     * @return human-readable command response
     * @throws Exception if any error occurs
     * */
    private String sendCommandAndRetrieveValue(PJLinkCommand command) throws Exception {
        String responseValue = retrieveResponseValue(sendCommandWithRetry(command.getValue(), command.getResponseTemplate()));

        if (responseValue.equals("ERR1")) {
            if (logger.isWarnEnabled()) {
                logger.warn("Undefined Command: " + new String(command.getValue()));
            }
            return "";
        } else if(responseValue.equals("ERR2")) {
            if (logger.isWarnEnabled()) {
                logger.warn("Out of parameter: " + new String(command.getValue()));
            }
            return "";
        } else if(responseValue.equals("ERR3")) {
            if (logger.isWarnEnabled()) {
                logger.warn("Unavailable time: " + new String(command.getValue()));
            }
            return "";
        } else if(responseValue.equals("ERR4")) {
            if (logger.isWarnEnabled()) {
                logger.warn("Projector/Display failure: " + new String(command.getValue()));
            }
            return "";
        }
        return responseValue;
    }

    /**
     *
     * */
    private String authorize(byte[] data) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("An attempt to authorize with command: " + new String(data));
        }
        String digest = DigestUtils.md5Hex(String.format("%s%s", authenticationSuffix, password).getBytes());
        authenticationRequired = false;
        byte[] command = String.format("%s%s", digest, new String(data)).getBytes();
        String response = sendCommand(command);
        if (response.contains(PJLINK_ERRA)) {
            throw new FailedLoginException();
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
        if (classResponse.equals("1")) {
            pjLinkClass = CLASS_1;
        } else if (classResponse.equals("2")){
            pjLinkClass = CLASS_2;
        }
        statistics.put(PJLINK_CLASS_PROPERTY, classResponse);
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
        statistics.put(POWER_PROPERTY, powrResponse);
        controls.add(createSwitch(POWER_PROPERTY, Objects.equals("1", powrResponse) ? 1 : 0));
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
        if (!StringUtils.isNullOrEmpty(freezeResponse)) {
            statistics.put(FREEZE_PROPERTY, freezeResponse);
            controls.add(createSwitch(FREEZE_PROPERTY, Objects.equals("1", freezeResponse) ? 1 : 0));
        }
    }

    /**
     * Create mic and speaker controls
     *
     * @param statistics to keep volume properties
     * @param controls to keep volume controls
     * */
    private void populateVolumeControls(Map<String, String> statistics, List<AdvancedControllableProperty> controls) {
        statistics.put(MICROPHONE_VOLUME_UP, "");
        controls.add(createButton(MICROPHONE_VOLUME_UP, "+", "+", 0L));

        statistics.put(MICROPHONE_VOLUME_DOWN, "");
        controls.add(createButton(MICROPHONE_VOLUME_DOWN, "-", "-", 0L));

        statistics.put(SPEAKER_VOLUME_UP, "");
        controls.add(createButton(SPEAKER_VOLUME_UP, "+", "+", 0L));

        statistics.put(SPEAKER_VOLUME_DOWN, "");
        controls.add(createButton(SPEAKER_VOLUME_DOWN, "-", "-", 0L));
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
        if (!StringUtils.isNullOrEmpty(inptResponse)) {
            String dropdownValue = inputOptions.inverse().get(inptResponse);
            statistics.put(INPUT_PROPERTY, dropdownValue);
            controls.add(createDropdown(INPUT_PROPERTY, new ArrayList<>(inputOptions.keySet()), dropdownValue));
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
                createAudioMuteControl(statistics, controls, "0");
                createVideoMuteControl(statistics, controls, "0");
                break;
            case "31":
                createAudioMuteControl(statistics, controls, "1");
                createVideoMuteControl(statistics, controls, "1");
                break;
            case "21":
                createAudioMuteControl(statistics, controls, "1");
                createVideoMuteControl(statistics, controls, "0");
                break;
            case "11":
                createAudioMuteControl(statistics, controls, "0");
                createVideoMuteControl(statistics, controls, "1");
                break;
            default:
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("AVMute parameter for AVMT response %s is not implemented.", avMuteResponse));
                }
                break;
        }
    }

    /**
     * Create audio mute control
     * @param statistics map to keep property in
     * @param controls list to keep controllable property in
     * @param value current value of the property
     * */
    private void createAudioMuteControl(Map<String, String> statistics, List<AdvancedControllableProperty> controls, String value) {
        statistics.put(AUDIOMUTE_PROPERTY, value);
        controls.add(createSwitch(AUDIOMUTE_PROPERTY, Integer.parseInt(value)));
    }

    /**
     * Create video mute control
     * @param statistics map to keep property in
     * @param controls list to keep controllable property in
     * @param value current value of the property
     * */
    private void createVideoMuteControl(Map<String, String> statistics, List<AdvancedControllableProperty> controls, String value) {
        statistics.put(VIDEOMUTE_PROPERTY, value);
        controls.add(createSwitch(VIDEOMUTE_PROPERTY, Integer.parseInt(value)));
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

        statistics.put(ERROR_FAN_PROPERTY, errorStatusString(fanError));
        statistics.put(ERROR_LAMP_PROPERTY, errorStatusString(lampError));
        statistics.put(ERROR_TEMPERATURE_PROPERTY, errorStatusString(temperatureError));
        statistics.put(ERROR_COVER_PROPERTY, errorStatusString(coverOpenError));
        statistics.put(ERROR_FILTER_PROPERTY, errorStatusString(filterError));
        statistics.put(ERROR_OTHER_PROPERTY, errorStatusString(otherError));
    }

    /**
     * Provides value for error status property
     *
     * @param id positional digit extracted from the PJLink ERRST response
     * @return human-readable definition of an error status
     * */
    private String errorStatusString(char id) {
        if (id == '0') {
            return "OK";
        } else if (id == '1') {
            return "WARNING";
        } else if (id == '2') {
            return "ERROR";
        }
        return "N/A";
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
                statistics.put(String.format("Lamp#Lamp%sStatus", lampIndex), "1".equals(lampsData[i]) ? "ON" : "OFF");
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
     * Update local statistics state, to provide when emergency delivery cycle is supposed to be skipped
     *
     * @param name property name
     * @param value property value
     * */
    private void updateLocalControllableProperty(String name, String value) {
        if (localStatistics != null) {
            localStatistics.getControllableProperties().stream().filter(advancedControllableProperty -> name.equals(advancedControllableProperty.getName())).forEach(advancedControllableProperty -> {
                advancedControllableProperty.setValue(value);
            });
            localStatistics.getStatistics().put(name, value);
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
}
