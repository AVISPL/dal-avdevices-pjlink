/*
 * Copyright (c) 2022 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.device.pjlink;

import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import org.junit.jupiter.api.Assertions;
import org.junit.Before;
import org.junit.Test;
import org.springframework.util.Assert;

import javax.security.auth.login.FailedLoginException;
import java.util.List;
import java.util.Map;

import static com.avispl.symphony.dal.device.pjlink.PJLinkConstants.*;

/**
 * E2E tests for PJLink communicator
 *
 * @author Maksym.Rossiytsev/AVISPL Team
 * */
public class PJLinkCommunicatorDeviceTest {
    PJLinkCommunicator communicator;

    @Before
    public void setUp() throws Exception {
        communicator = new PJLinkCommunicator();
        communicator.setPort(4352);
        //communicator.setHost("***REMOVED***"); // NEC Projector
        communicator.setPassword("1234");
        communicator.setHost("172.31.254.236"); // NEC Display
    }

    @Test
    public void testGetMultipleStatistics() throws Exception {
        communicator.setConnectionKeepAliveTimeout(20000);
        communicator.init();
        List<Statistics> statisticsList = communicator.getMultipleStatistics();
        ExtendedStatistics statistics = (ExtendedStatistics)statisticsList.get(0);
        Map<String, String> statisticsMap = statistics.getStatistics();

        Assert.notNull(statisticsList, "Statistics must not be null!");
        Assert.notEmpty(statistics.getControllableProperties(), "Controls must not be empty!");
        Assertions.assertEquals("1.0.0", statisticsMap.get(METADATA_ADAPTER_VERSION_PROPERTY));
        Assertions.assertEquals("PA804UL", statisticsMap.get(PRODUCT_DETAILS_PROPERTY));
        Assertions.assertEquals("DisplayPort", statisticsMap.get(INPUT_PROPERTY));
        Assertions.assertEquals("0", statisticsMap.get(VIDEOMUTE_PROPERTY));
        Assertions.assertEquals("0", statisticsMap.get(AUDIOMUTE_PROPERTY));
    }
    @Test(expected = FailedLoginException.class)
    public void testGetMultipleStatisticsAuthFail() throws Exception {
        communicator.setConnectionKeepAliveTimeout(20000);
        communicator.setPassword("");
        communicator.init();
        List<Statistics> statisticsList = communicator.getMultipleStatistics();
        ExtendedStatistics statistics = (ExtendedStatistics)statisticsList.get(0);
        statistics.getStatistics();
    }

    @Test
    public void testGetMultipleStatisticsWithPauseAndKeepAlive() throws Exception {
        communicator.setConnectionKeepAliveTimeout(20000);
        communicator.init();
        List<Statistics> statisticsList = communicator.getMultipleStatistics();
        System.out.println("First idle period");
        Thread.sleep(60000);
        statisticsList = communicator.getMultipleStatistics();
        System.out.println("Second idle period");
        Thread.sleep(60000);
        System.out.println("Third idle period");
        Thread.sleep(60000);
        System.out.println("Fourth idle period");
        Thread.sleep(60000);
        System.out.println("Fifth idle period");
        Thread.sleep(60000);
        statisticsList = communicator.getMultipleStatistics();
        ExtendedStatistics statistics = (ExtendedStatistics)statisticsList.get(0);
        Map<String, String> statisticsMap = statistics.getStatistics();

        Assert.notNull(statisticsList, "Statistics must not be null!");
        Assert.notEmpty(statistics.getControllableProperties(), "Controls must not be empty!");
        Assertions.assertEquals("1.0.0", statisticsMap.get(METADATA_ADAPTER_VERSION_PROPERTY));
        Assertions.assertEquals("PA804UL", statisticsMap.get(PRODUCT_DETAILS_PROPERTY));
        Assertions.assertEquals("DisplayPort", statisticsMap.get(INPUT_PROPERTY));
        Assertions.assertEquals("0", statisticsMap.get(VIDEOMUTE_PROPERTY));
        Assertions.assertEquals("0", statisticsMap.get(AUDIOMUTE_PROPERTY));
    }

    @Test
    public void testPowerSwitch() throws Exception {
        communicator.setConnectionKeepAliveTimeout(20000);
        communicator.init();
        List<Statistics> statisticsList = communicator.getMultipleStatistics();
        Thread.sleep(60000);

        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty(PJLinkConstants.POWER_PROPERTY);
        controllableProperty.setValue("1");
        communicator.controlProperty(controllableProperty);
        statisticsList = communicator.getMultipleStatistics();

        ExtendedStatistics statistics = (ExtendedStatistics)statisticsList.get(0);
        Map<String, String> statisticsMap = statistics.getStatistics();
        Assertions.assertEquals("1", statisticsMap.get(PJLinkConstants.POWER_PROPERTY));
    }

    @Test
    public void testInputChange() throws Exception {
        communicator.setConnectionKeepAliveTimeout(20000);
        communicator.init();
        List<Statistics> statisticsList = communicator.getMultipleStatistics();
        //Thread.sleep(60000);

        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty(PJLinkConstants.INPUT_PROPERTY);
        controllableProperty.setValue("HDMI1");
        communicator.controlProperty(controllableProperty);
        statisticsList = communicator.getMultipleStatistics();

        ExtendedStatistics statistics = (ExtendedStatistics)statisticsList.get(0);
        Map<String, String> statisticsMap = statistics.getStatistics();
        Assertions.assertEquals("HDMI1", statisticsMap.get(PJLinkConstants.INPUT_PROPERTY));
    }

    @Test
    public void testAudioMute() throws Exception {
        communicator.setConnectionKeepAliveTimeout(20000);
        communicator.init();
        List<Statistics> statisticsList = communicator.getMultipleStatistics();
        Thread.sleep(60000);

        ControllableProperty controllableProperty = new ControllableProperty();
        controllableProperty.setProperty(AUDIOMUTE_PROPERTY);
        controllableProperty.setValue("1");
        communicator.controlProperty(controllableProperty);
        statisticsList = communicator.getMultipleStatistics();

        ExtendedStatistics statistics = (ExtendedStatistics)statisticsList.get(0);
        Map<String, String> statisticsMap = statistics.getStatistics();
        Assertions.assertEquals("1", statisticsMap.get(AUDIOMUTE_PROPERTY));
    }
}
