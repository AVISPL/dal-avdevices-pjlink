/*
 * Copyright (c) 2022 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.device.pjlink;

/**
 * PJLink adapter constants - Property names, fixed responses etc.
 *
 * @author Maksym.Rossiytsev/AVISPL Team
 */
public class PJLinkConstants {
    public static final String PJLINK_0 = "PJLINK 0";
    public static final String PJLINK_1 = "PJLINK 1";
    public static final String PJLINK_ERRA = "PJLINK ERRA";
    public static final String UNDEFINED_COMMAND = "ERR1";
    public static final String OUT_OF_PARAMETER = "ERR2";
    public static final String UNAVAILABLE_TIME = "ERR3";
    public static final String DEVICE_FAILURE = "ERR4";

    public static final String ERRA_TEXT = "ERR_LOGIN";
    public static final String UNDEFINED_COMMAND_TEXT = "ERR_UNSUPPORTED";
    public static final String OUT_OF_PARAMETER_TEXT = "ERR_PARAMETER";
    public static final String UNAVAILABLE_TIME_TEXT = "ERR_DEVICE_STATE";
    public static final String DEVICE_FAILURE_TEXT = "ERR_DEVICE_FAILURE";
    public static final String N_A = "N/A";

    public static final String STATUS_OK = "OK";
    public static final String STATUS_WARNING = "WARNING";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_OFF = "OFF";
    public static final String STATUS_ON = "ON";

    public static final String POWER_PROPERTY = "System#Power";
    public static final String FREEZE_PROPERTY = "System#Freeze";
    public static final String SPEAKER_VOLUME_UP = "Audio#SpeakerVolumeUp";
    public static final String SPEAKER_VOLUME_DOWN = "Audio#SpeakerVolumeDown";
    public static final String MICROPHONE_VOLUME_UP = "Audio#MicrophoneVolumeUp";
    public static final String MICROPHONE_VOLUME_DOWN = "Audio#MicrophoneVolumeDown";
    public static final String INPUT_PROPERTY = "System#Input";
    public static final String AUDIOMUTE_PROPERTY = "Audio#AudioMute";
    public static final String VIDEOMUTE_PROPERTY = "System#VideoMute";
    public static final String DEVICE_NAME_PROPERTY = "DeviceName";
    public static final String MANUFACTURER_DETAILS_PROPERTY = "ManufacturerDetails";
    public static final String PRODUCT_DETAILS_PROPERTY = "ProductDetails";
    public static final String DEVICE_DETAILS_PROPERTY = "DeviceDetails";
    public static final String SERIAL_NUMBER_PROPERTY = "SerialNumber";
    public static final String SOFTWARE_VERSION_PROPERTY = "SoftwareVersion";
    public static final String FILTER_USAGE_PROPERTY = "System#FilterUsageTime(hours)";
    public static final String LAMP_USAGE_PROPERTY = "Lamp#Lamp%sUsageTime(hours)";
    public static final String LAMP_STATUS_PROPERTY = "Lamp#Lamp%sStatus";
    public static final String FILTER_REPLACEMENT_PROPERTY = "System#FilterReplacementModelNumber";
    public static final String LAMP_REPLACEMENT_PROPERTY = "Lamp#LampReplacementModelNumber";
    public static final String RECOMMENDED_RESOLUTION_PROPERTY = "System#RecommendedResolution";
    public static final String INPUT_RESOLUTION_PROPERTY = "System#InputResolution";
    public static final String PJLINK_CLASS_PROPERTY = "PJLinkClass";

    public static final String ERROR_FAN_PROPERTY = "ErrorStatus#Fan";
    public static final String ERROR_LAMP_PROPERTY = "ErrorStatus#Lamp";
    public static final String ERROR_TEMPERATURE_PROPERTY = "ErrorStatus#Temperature";
    public static final String ERROR_COVER_PROPERTY = "ErrorStatus#CoverOpen";
    public static final String ERROR_FILTER_PROPERTY = "ErrorStatus#Filter";
    public static final String ERROR_OTHER_PROPERTY = "ErrorStatus#Other";

    public static final String METADATA_ADAPTER_VERSION_PROPERTY = "AdapterMetadata#AdapterVersion";
    public static final String METADATA_ADAPTER_BUILD_DATE_PROPERTY = "AdapterMetadata#AdapterBuildDate";
    public static final String METADATA_ADAPTER_UPTIME_PROPERTY = "AdapterMetadata#AdapterUptime";

    public enum PJLinkClass {
        CLASS_1, CLASS_2
    }
}
