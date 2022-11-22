/*
 * Copyright (c) 2022 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.device.pjlink.error;

/**
 * Exception that indicates that the PJLink device is experiencing general failure
 * @author Maksym.Rossiitsev/AVISPL Team
 * */
public class PJLinkDeviceFailureException extends RuntimeException {
    /**
     * Parameterized constructor
     *
     * @param message to log and include into a stacktrace whenever the error occurs
     * */
    public PJLinkDeviceFailureException(String message) {
        super(message);
    }
}
