/*
 * Copyright (c) 2022 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.device.pjlink;

import java.util.Arrays;
import java.util.Optional;

/**
 * {@link #POWR_CMD}: 2nd to last byte 1 or 0
 * {@link #INPT_CMD}: 2nd to last byte - values 1-9 for Class 1 and 1-9/A-Z for Class 2
 *                    3rd to last byte - 1-6 for RGB, VIDEO, DIGITAL, STORAGE, NETWORK, INTERNAL respectively
 * {@link #AUDIO_MUTE_CMD} {@link #VIDEO_MUTE_CMD}: 2nd to last byte - 1 or 0
 * {@link #INNM_STAT}: 2nd and 3rd to last bytes - The numbers of switchable input source 11 to 6Z
 *                     (terminal numbers that can be acquired with the INST command)
 * {@link #SVOL_CMD}: 2nd to last byte - 0 to decrease speaker volume by 1, 1 to increase by 1
 * {@link #MVOL_CMD}: 2nd to last byte - 0 to decrease speaker volume by 1, 1 to increase by 1
 * {@link #FREZ_CMD}: 2nd to last byte - 1 to freeze the screen, 0 to cancel freeze
 *
 * @author Maksym.Rossiitsev/AVISPL Team
 */
public enum PJLinkCommand {
    // Class 1 properties
    POWR_CMD(new byte[]{0x25, 0x31, 0x50, 0x4f, 0x57, 0x52, 0x20, 0x00, 0x0d}, "POWR"),
    POWR_STAT(new byte[]{0x25, 0x31, 0x50, 0x4f, 0x57, 0x52, 0x20, 0x3f, 0x0d}, "POWR"),
    INPT_CMD(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x50, 0x54, 0x20, 0x00, 0x00, 0x0d}, "INPT"),
    INPT_STAT(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x50, 0x54, 0x20, 0x3f, 0x0d}, "INPT"),
    AUDIO_MUTE_CMD(new byte[] {0x25, 0x31, 0x41, 0x56, 0x4d, 0x54, 0x20, 0x32, 0x00, 0x0d}, "AVMT"),
    VIDEO_MUTE_CMD(new byte[] {0x25, 0x31, 0x41, 0x56, 0x4d, 0x54, 0x20, 0x31, 0x00, 0x0d}, "AVMT"),
    AVMT_STAT(new byte[]{0x25, 0x31, 0x41, 0x56, 0x4d, 0x54, 0x20, 0x3f, 0x0d}, "AVMT"),
    ERST_STAT(new byte[]{0x25, 0x31, 0x45, 0x52, 0x53, 0x54, 0x20, 0x3f, 0x0d}, "ERST"),
    LAMP_STAT(new byte[]{0x25, 0x31, 0x4c, 0x41, 0x4d, 0x50, 0x20, 0x3f, 0x0d}, "LAMP"),
    INST_STAT_C1(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x53, 0x54, 0x20, 0x3f, 0x0d}, "INST"),
    INST_STAT_C2(new byte[]{0x25, 0x32, 0x49, 0x4e, 0x53, 0x54, 0x20, 0x3f, 0x0d}, "INST"),
    NAME_STAT(new byte[]{0x25, 0x31, 0x4e, 0x41, 0x4d, 0x45, 0x20, 0x3f, 0x0d}, "NAME"),
    INF1_STAT(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x46, 0x31, 0x20, 0x3f, 0x0d}, "INF1"),
    INF2_STAT(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x46, 0x32, 0x20, 0x3f, 0x0d}, "INF2"),
    INFO_STAT(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x46, 0x4f, 0x20, 0x3f, 0x0d}, "INFO"),
    CLSS_STAT(new byte[]{0x25, 0x31, 0x43, 0x4c, 0x53, 0x53, 0x20, 0x3f, 0x0d}, "CLSS"),
    // Class 2 properties
    SNUM_STAT(new byte[]{0x25, 0x32, 0x53, 0x4e, 0x55, 0x4d, 0x20, 0x3f, 0x0d}, "SNUM"),
    SVER_STAT(new byte[]{0x25, 0x32, 0x53, 0x56, 0x45, 0x52, 0x20, 0x3f, 0x0d}, "SVER"),
    INNM_STAT(new byte[]{0x25, 0x32, 0x49, 0x4e, 0x4e, 0x4d, 0x20, 0x3f, 0x00, 0x00, 0x0d}, "INNM"),
    IRES_STAT(new byte[]{0x25, 0x32, 0x49, 0x52, 0x45, 0x53, 0x20, 0x3f, 0x0d}, "IRES"),
    RRES_STAT(new byte[]{0x25, 0x32, 0x52, 0x52, 0x45, 0x53, 0x20, 0x3f, 0x0d}, "RRES"),
    FILT_STAT(new byte[]{0x25, 0x32, 0x46, 0x49, 0x4c, 0x54, 0x20, 0x3f, 0x0d}, "FILT"),
    RLMP_STAT(new byte[]{0x25, 0x32, 0x52, 0x4c, 0x4d, 0x50, 0x20, 0x3f, 0x0d}, "RLMP"),
    RFIL_STAT(new byte[]{0x25, 0x32, 0x52, 0x46, 0x49, 0x4c, 0x20, 0x3f, 0x0d}, "RFIL"),
    SVOL_CMD(new byte[]{0x25, 0x32, 0x53, 0x56, 0x4f, 0x4c, 0x20, 0x00, 0x0d}, "SVOL"),
    MVOL_CMD(new byte[]{0x25, 0x32, 0x4d, 0x56, 0x4f, 0x4c, 0x20, 0x00, 0x0d}, "MVOL"),
    FREZ_CMD(new byte[]{0x25, 0x32, 0x46, 0x52, 0x45, 0x5a, 0x20, 0x00, 0x0d}, "FREZ"),
    FREZ_STAT(new byte[]{0x25, 0x32, 0x46, 0x52, 0x45, 0x5a, 0x20, 0x3f, 0x0d}, "FREZ"),
    BLANK(new byte[]{}, "");

    private final byte[] value;
    private final String responseTemplate;
    PJLinkCommand(final byte[] value, final String responseTemplate) {
        this.value = value;
        this.responseTemplate = responseTemplate;
    }

    /**
     * Retrieves {@link #responseTemplate}
     *
     * @return value of {@link #responseTemplate}
     */
    public String getResponseTemplate() {
        return responseTemplate;
    }

    /**
     * Retrieves {@link #value}
     *
     * @return value of {@link #value}
     */
    public byte[] getValue() {
        return value;
    }

    /**
     * Check if command exists and get its name
     *
     * @param value of the property to check
     * @return true if definition exists, false otherwise
     * */
    public static Optional<PJLinkCommand> findByByteSequence(byte[] value) {
        return Arrays.stream(PJLinkCommand.values()).filter(c -> Arrays.equals(value, c.getValue())).findFirst();
    }
}
