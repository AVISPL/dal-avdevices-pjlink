/*
 * Copyright (c) 2022 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.device.pjlink;

import com.avispl.symphony.api.dal.dto.control.ConnectionState;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.dal.communicator.ConnectionStatus;
import com.avispl.symphony.dal.communicator.SocketCommunicator;
import org.junit.Before;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.api.support.membermodification.MemberMatcher;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import javax.security.auth.login.FailedLoginException;
import java.util.List;
import java.util.Map;

import static com.avispl.symphony.dal.device.pjlink.PJLinkConstants.*;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.*;

/**
 * Unit tests for PJLink communicator
 *
 * @author Maksym.Rossiytsev/AVISPL Team
 * */
@RunWith(PowerMockRunner.class)
@PrepareForTest({SocketCommunicator.class})
public class PJLinkCommunicatorTest {
    @Spy
    PJLinkCommunicator pjLinkCommunicator = spy(new PJLinkCommunicator());

    @Before
    public void setUp() throws Exception {
        pjLinkCommunicator.setHost("172.0.0.1");
        pjLinkCommunicator.setPort(8888);

        PowerMockito.suppress(MemberMatcher.methodsDeclaredIn(SocketCommunicator.class));
        pjLinkCommunicator.init();

        when(pjLinkCommunicator.send(PJLinkCommand.CLSS_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x43, 0x4c, 0x53, 0x53, 0x3d, 0x32, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.POWR_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x50, 0x4f, 0x57, 0x52, 0x3d, 0x30, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.INPT_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x50, 0x54, 0x3d, 0x31, 0x31, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.INST_STAT_C2.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x53, 0x54, 0x3d, 0x31, 0x31, 0x20, 0x33, 0x31, 0x20, 0x33, 0x32, 0x20, 0x33, 0x33, 0x20, 0x35, 0x31, 0x20, 0x36, 0x31, 0x0d});

        byte[] innm1 = PJLinkCommand.INNM_STAT.getValue().clone();
        innm1[8] = 0x31;
        innm1[9] = 0x31;
        byte[] innm2 = PJLinkCommand.INNM_STAT.getValue().clone();
        innm2[8] = 0x33;
        innm2[9] = 0x31;
        byte[] innm3 = PJLinkCommand.INNM_STAT.getValue().clone();
        innm3[8] = 0x33;
        innm3[9] = 0x32;
        byte[] innm4 = PJLinkCommand.INNM_STAT.getValue().clone();
        innm4[8] = 0x33;
        innm4[9] = 0x33;
        byte[] innm5 = PJLinkCommand.INNM_STAT.getValue().clone();
        innm5[8] = 0x35;
        innm5[9] = 0x31;
        byte[] innm6 = PJLinkCommand.INNM_STAT.getValue().clone();
        innm6[8] = 0x36;
        innm6[9] = 0x31;
        when(pjLinkCommunicator.send(innm1)).thenReturn(new byte[]{0x25, 0x32, 0x49, 0x4e, 0x4e, 0x4d, 0x3d, 0x43, 0x4f, 0x4d, 0x50, 0x55, 0x54, 0x45, 0x52, 0x0d});
        when(pjLinkCommunicator.send(innm2)).thenReturn(new byte[]{0x25, 0x32, 0x49, 0x4e, 0x4e, 0x4d, 0x3d, 0x43, 0x4f, 0x4d, 0x50, 0x55, 0x55, 0x45, 0x52, 0x0d});
        when(pjLinkCommunicator.send(innm3)).thenReturn(new byte[]{0x25, 0x32, 0x49, 0x4e, 0x4e, 0x4d, 0x3d, 0x43, 0x4f, 0x4d, 0x50, 0x55, 0x56, 0x45, 0x52, 0x0d});
        when(pjLinkCommunicator.send(innm4)).thenReturn(new byte[]{0x25, 0x32, 0x49, 0x4e, 0x4e, 0x4d, 0x3d, 0x43, 0x4f, 0x4d, 0x50, 0x55, 0x57, 0x45, 0x52, 0x0d});
        when(pjLinkCommunicator.send(innm5)).thenReturn(new byte[]{0x25, 0x32, 0x49, 0x4e, 0x4e, 0x4d, 0x3d, 0x43, 0x4f, 0x4d, 0x50, 0x55, 0x58, 0x45, 0x52, 0x0d});
        when(pjLinkCommunicator.send(innm6)).thenReturn(new byte[]{0x25, 0x32, 0x49, 0x4e, 0x4e, 0x4d, 0x3d, 0x43, 0x4f, 0x4d, 0x50, 0x55, 0x59, 0x45, 0x52, 0x0d});

        when(pjLinkCommunicator.send(PJLinkCommand.AVMT_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x41, 0x56, 0x4d, 0x54, 0x3d, 0x33, 0x31, 0x0d});

        when(pjLinkCommunicator.send(PJLinkCommand.INST_STAT_C1.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x53, 0x54, 0x3d, 0x33, 0x31, 0x20, 0x33, 0x32, 0x20, 0x33, 0x33, 0x20, 0x33, 0x34, 0x20, 0x33, 0x35, 0x20, 0x33, 0x36, 0x0d});//
        when(pjLinkCommunicator.send(PJLinkCommand.ERST_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x45, 0x52, 0x53, 0x54, 0x3d, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.LAMP_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x4c, 0x41, 0x4d, 0x50, 0x3d, 0x45, 0x52, 0x52, 0x31, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.NAME_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x4e, 0x41, 0x4d, 0x45, 0x3d, 0x52, 0x45, 0x41, 0x4c, 0x20, 0x4e, 0x41, 0x4d, 0x45, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.INF1_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x46, 0x31, 0x3d, 0x4d, 0x4f, 0x44, 0x45, 0x4c, 0x5f, 0x4e, 0x41, 0x4d, 0x45, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.INF2_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x46, 0x32, 0x3d, 0x4d, 0x61, 0x6e, 0x75, 0x66, 0x61, 0x63, 0x74, 0x75, 0x72, 0x65, 0x72, 0x20, 0x69, 0x6e, 0x66, 0x6f, 0x72, 0x6d, 0x61, 0x74, 0x69, 0x6f, 0x6e, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.INFO_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x46, 0x4f, 0x3d, 0x47, 0x65, 0x6e, 0x65, 0x72, 0x61, 0x6c, 0x20, 0x61, 0x64, 0x64, 0x69, 0x74, 0x69, 0x6f, 0x6e, 0x61, 0x6c, 0x20, 0x69, 0x6e, 0x66, 0x6f, 0x0d});

        when(pjLinkCommunicator.send(PJLinkCommand.SNUM_STAT.getValue())).thenReturn(new byte[]{0x25, 0x32, 0x53, 0x4e, 0x55, 0x4d, 0x3d, 0x45, 0x52, 0x52, 0x33, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.SVER_STAT.getValue())).thenReturn(new byte[]{0x25, 0x32, 0x53, 0x56, 0x45, 0x52, 0x3d, 0x45, 0x52, 0x52, 0x33, 0x0d});
        //when(pjLinkCommunicator.send(PJLinkCommand.INST_STAT_C2.getValue())).thenReturn(new byte[]{0x25, 0x32, 0x49, 0x4e, 0x53, 0x54, 0x3d, 0x45, 0x52, 0x52, 0x33, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.FILT_STAT.getValue())).thenReturn(new byte[]{0x25, 0x32, 0x46, 0x49, 0x4c, 0x54, 0x3d, 0x45, 0x52, 0x52, 0x31, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.RFIL_STAT.getValue())).thenReturn(new byte[]{0x25, 0x32, 0x52, 0x46, 0x49, 0x4c, 0x3d, 0x45, 0x52, 0x52, 0x33, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.RLMP_STAT.getValue())).thenReturn(new byte[]{0x25, 0x32, 0x52, 0x4c, 0x4d, 0x50, 0x3d, 0x45, 0x52, 0x52, 0x33, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.FREZ_STAT.getValue())).thenReturn(new byte[]{0x25, 0x32, 0x46, 0x52, 0x45, 0x5a, 0x3d, 0x45, 0x52, 0x52, 0x31, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.RRES_STAT.getValue())).thenReturn(new byte[]{0x25, 0x32, 0x52, 0x52, 0x45, 0x53, 0x3d, 0x45, 0x52, 0x52, 0x33, 0x0d});
        when(pjLinkCommunicator.send(PJLinkCommand.IRES_STAT.getValue())).thenReturn(new byte[]{0x25, 0x32, 0x49, 0x52, 0x45, 0x53, 0x3d, 0x45, 0x52, 0x52, 0x33, 0x0d});

        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).thenReturn(new byte[]{});
    }

    @Test
    public void testGetMultipleStatisticsClass1NoAuth() throws Exception {
        pjLinkCommunicator.internalInit();
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setConnectionState(ConnectionState.Unknown);
        when(pjLinkCommunicator.getConnectionStatus()).thenReturn(connectionStatus);
        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).then(invocationOnMock -> {
            pjLinkCommunicator.getConnectionStatus().setConnectionState(ConnectionState.Connected);
            return new byte[]{0x50, 0x4a, 0x4c, 0x49, 0x4e, 0x4b, 0x20, 0x30, 0x0d};
        });
        when(pjLinkCommunicator.send(PJLinkCommand.CLSS_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x43, 0x4c, 0x53, 0x53, 0x3d, 0x31, 0x0d});

        List<Statistics> statistics = pjLinkCommunicator.getMultipleStatistics();
        ExtendedStatistics stats = (ExtendedStatistics) statistics.get(0);
        Map<String, String> statisticsMap = stats.getStatistics();

        Assertions.assertEquals("0", statisticsMap.get("System#Power"));
        Assertions.assertEquals("1", statisticsMap.get("PJLinkClass"));
        Assertions.assertEquals("General additional info", statisticsMap.get("DeviceDetails"));
        Assertions.assertEquals("Manufacturer information", statisticsMap.get("ProductDetails"));
        Assertions.assertEquals("MODEL_NAME", statisticsMap.get("ManufacturerDetails"));
        Assertions.assertEquals("REAL NAME", statisticsMap.get("DeviceName"));
    }

    @Test
    public void testGetMultipleStatisticsClass1Auth() throws Exception {
        pjLinkCommunicator.internalInit();
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setConnectionState(ConnectionState.Unknown);
        when(pjLinkCommunicator.getConnectionStatus()).thenReturn(connectionStatus);
        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).then(invocationOnMock -> {
            pjLinkCommunicator.getConnectionStatus().setConnectionState(ConnectionState.Connected);
            return new byte[]{0x50, 0x4a, 0x4c, 0x49, 0x4e, 0x4b, 0x20, 0x31, 0x20, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x0d};
        });
        when(pjLinkCommunicator.send(PJLinkCommand.CLSS_STAT.getValue())).thenReturn(new byte[]{0x50, 0x4a, 0x4c, 0x49, 0x4e, 0x4b, 0x20, 0x45, 0x52, 0x52, 0x41, 0x0d});
        when(pjLinkCommunicator.send(new byte[]{0x36, 0x62, 0x31, 0x61, 0x61, 0x30, 0x62, 0x61, 0x62, 0x35, 0x30, 0x61, 0x36, 0x32, 0x33, 0x62, 0x35, 0x63, 0x64, 0x65, 0x33, 0x34, 0x32, 0x32, 0x34, 0x65, 0x62, 0x36, 0x37, 0x35, 0x37, 0x33, 0x25, 0x31, 0x43, 0x4c, 0x53, 0x53, 0x20, 0x3f, 0x0d}))
                .thenReturn(new byte[]{0x25, 0x31, 0x43, 0x4c, 0x53, 0x53, 0x3d, 0x31, 0x0d});

        List<Statistics> statistics = pjLinkCommunicator.getMultipleStatistics();
        ExtendedStatistics stats = (ExtendedStatistics) statistics.get(0);
        Map<String, String> statisticsMap = stats.getStatistics();

        Assertions.assertEquals("0", statisticsMap.get("System#Power"));
        Assertions.assertEquals("1", statisticsMap.get("PJLinkClass"));
        Assertions.assertEquals("General additional info", statisticsMap.get("DeviceDetails"));
        Assertions.assertEquals("Manufacturer information", statisticsMap.get("ProductDetails"));
        Assertions.assertEquals("MODEL_NAME", statisticsMap.get("ManufacturerDetails"));
        Assertions.assertEquals("REAL NAME", statisticsMap.get("DeviceName"));
    }

    @Test(expected = FailedLoginException.class)
    public void testGetMultipleStatisticsAuthFail() throws Exception {
        pjLinkCommunicator.internalInit();
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setConnectionState(ConnectionState.Unknown);
        when(pjLinkCommunicator.getConnectionStatus()).thenReturn(connectionStatus);
        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).then(invocationOnMock -> {
            pjLinkCommunicator.getConnectionStatus().setConnectionState(ConnectionState.Connected);
            return new byte[]{0x50, 0x4a, 0x4c, 0x49, 0x4e, 0x4b, 0x20, 0x31, 0x20, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x0d};
        });
        when(pjLinkCommunicator.send(PJLinkCommand.CLSS_STAT.getValue())).thenReturn(new byte[]{0x50, 0x4a, 0x4c, 0x49, 0x4e, 0x4b, 0x20, 0x45, 0x52, 0x52, 0x41, 0x0d});
        when(pjLinkCommunicator.send(new byte[]{0x36, 0x62, 0x31, 0x61, 0x61, 0x30, 0x62, 0x61, 0x62, 0x35, 0x30, 0x61, 0x36, 0x32, 0x33, 0x62, 0x35, 0x63, 0x64, 0x65, 0x33, 0x34, 0x32, 0x32, 0x34, 0x65, 0x62, 0x36, 0x37, 0x35, 0x37, 0x33, 0x25, 0x31, 0x43, 0x4c, 0x53, 0x53, 0x20, 0x3f, 0x0d}))
                .thenReturn(new byte[]{0x50, 0x4a, 0x4c, 0x49, 0x4e, 0x4b, 0x20, 0x45, 0x52, 0x52, 0x41, 0x0d});

        pjLinkCommunicator.getMultipleStatistics();
    }

    @Test
    public void testGetMultipleStatisticsClass2NoAuth() throws Exception {
        pjLinkCommunicator.internalInit();
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setConnectionState(ConnectionState.Unknown);
        when(pjLinkCommunicator.getConnectionStatus()).thenReturn(connectionStatus);
        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).then(invocationOnMock -> {
            pjLinkCommunicator.getConnectionStatus().setConnectionState(ConnectionState.Connected);
            return new byte[]{};
        });
        when(pjLinkCommunicator.send(PJLinkCommand.CLSS_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x43, 0x4c, 0x53, 0x53, 0x3d, 0x32, 0x0d});

        List<Statistics> statistics = pjLinkCommunicator.getMultipleStatistics();
        ExtendedStatistics stats = (ExtendedStatistics) statistics.get(0);
        Map<String, String> statisticsMap = stats.getStatistics();

        Assertions.assertEquals("COMPUTER", statisticsMap.get(INPUT_PROPERTY));
        Assertions.assertEquals("0", statisticsMap.get(POWER_PROPERTY));
        Assertions.assertEquals("2", statisticsMap.get(PJLINK_CLASS_PROPERTY));
        Assertions.assertEquals("General additional info", statisticsMap.get(DEVICE_DETAILS_PROPERTY));
        Assertions.assertEquals("Manufacturer information", statisticsMap.get(PRODUCT_DETAILS_PROPERTY));
        Assertions.assertEquals("MODEL_NAME", statisticsMap.get(MANUFACTURER_DETAILS_PROPERTY));
        Assertions.assertEquals("REAL NAME", statisticsMap.get(DEVICE_NAME_PROPERTY));
    }

    @Test
    public void testGetMultipleStatisticsClass2Auth() throws Exception {
        pjLinkCommunicator.internalInit();
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setConnectionState(ConnectionState.Unknown);
        when(pjLinkCommunicator.getConnectionStatus()).thenReturn(connectionStatus);
        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).then(invocationOnMock -> {
            pjLinkCommunicator.getConnectionStatus().setConnectionState(ConnectionState.Connected);
            return new byte[]{0x50, 0x4a, 0x4c, 0x49, 0x4e, 0x4b, 0x20, 0x31, 0x20, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x0d};
        });
        when(pjLinkCommunicator.send(PJLinkCommand.CLSS_STAT.getValue())).thenReturn(new byte[]{0x50, 0x4a, 0x4c, 0x49, 0x4e, 0x4b, 0x20, 0x45, 0x52, 0x52, 0x41, 0x0d});
        when(pjLinkCommunicator.send(new byte[]{0x36, 0x62, 0x31, 0x61, 0x61, 0x30, 0x62, 0x61, 0x62, 0x35, 0x30, 0x61, 0x36, 0x32, 0x33, 0x62, 0x35, 0x63, 0x64, 0x65, 0x33, 0x34, 0x32, 0x32, 0x34, 0x65, 0x62, 0x36, 0x37, 0x35, 0x37, 0x33, 0x25, 0x31, 0x43, 0x4c, 0x53, 0x53, 0x20, 0x3f, 0x0d}))
                .thenReturn(new byte[]{0x25, 0x31, 0x43, 0x4c, 0x53, 0x53, 0x3d, 0x32, 0x0d});

        List<Statistics> statistics = pjLinkCommunicator.getMultipleStatistics();
        ExtendedStatistics stats = (ExtendedStatistics) statistics.get(0);
        Map<String, String> statisticsMap = stats.getStatistics();

        Assertions.assertEquals("0", statisticsMap.get("System#Power"));
        Assertions.assertEquals("2", statisticsMap.get("PJLinkClass"));
        Assertions.assertEquals("General additional info", statisticsMap.get("DeviceDetails"));
        Assertions.assertEquals("Manufacturer information", statisticsMap.get("ProductDetails"));
        Assertions.assertEquals("MODEL_NAME", statisticsMap.get("ManufacturerDetails"));
        Assertions.assertEquals("REAL NAME", statisticsMap.get("DeviceName"));
    }

    @Test
    public void testControlPowerNoAuth() throws Exception {
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setConnectionState(ConnectionState.Unknown);
        when(pjLinkCommunicator.getConnectionStatus()).thenReturn(connectionStatus);
        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).then(invocationOnMock -> {
            pjLinkCommunicator.getConnectionStatus().setConnectionState(ConnectionState.Connected);
            return new byte[]{};
        });
        when(pjLinkCommunicator.send(PJLinkCommand.CLSS_STAT.getValue())).thenReturn(new byte[]{0x25, 0x31, 0x43, 0x4c, 0x53, 0x53, 0x3d, 0x32, 0x0d});
        byte[] powerCmd = PJLinkCommand.POWR_CMD.getValue().clone();
        powerCmd[7] = 0x31;
        when(pjLinkCommunicator.send(powerCmd)).thenReturn(new byte[]{0x25, 0x31, 0x50, 0x4f, 0x57, 0x52, 0x3d, 0x31, 0x0d});

        ControllableProperty powerControl = new ControllableProperty();
        powerControl.setValue("1");
        powerControl.setProperty(PJLinkConstants.POWER_PROPERTY);
        pjLinkCommunicator.controlProperty(powerControl);

        ArgumentCaptor<byte[]> command = ArgumentCaptor.forClass(byte[].class);
        verify(pjLinkCommunicator, Mockito.atLeastOnce()).send(command.capture());
        Assertions.assertEquals(new String(powerCmd), new String(command.getValue()));
    }

    @Test
    public void testControlAuth() throws Exception {
        pjLinkCommunicator.internalInit();
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setConnectionState(ConnectionState.Unknown);
        when(pjLinkCommunicator.getConnectionStatus()).thenReturn(connectionStatus);
        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).then(invocationOnMock -> {
            pjLinkCommunicator.getConnectionStatus().setConnectionState(ConnectionState.Connected);
            return new byte[]{0x50, 0x4a, 0x4c, 0x49, 0x4e, 0x4b, 0x20, 0x31, 0x20, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30, 0x0d};
        });
        when(pjLinkCommunicator.send(new byte[]{0x25, 0x31, 0x50, 0x4f, 0x57, 0x52, 0x20, 0x31, 0x0d})).thenReturn(new byte[]{0x25, 0x31, 0x50, 0x4f, 0x57, 0x52, 0x3d, 0x4f, 0x4b, 0x0d});
        byte[] powerOnAuthCmd = new byte[]{0x36, 0x62, 0x31, 0x61, 0x61, 0x30, 0x62, 0x61, 0x62, 0x35, 0x30, 0x61, 0x36, 0x32, 0x33, 0x62, 0x35, 0x63, 0x64, 0x65, 0x33, 0x34, 0x32, 0x32, 0x34, 0x65, 0x62, 0x36, 0x37, 0x35, 0x37, 0x33, 0x25, 0x31, 0x50, 0x4f, 0x57, 0x52, 0x20, 0x31, 0x0d};
        when(pjLinkCommunicator.send(powerOnAuthCmd)).thenReturn(new byte[]{0x25, 0x31, 0x50, 0x4f, 0x57, 0x52, 0x3d, 0x31, 0x0d});

        ControllableProperty powerControl = new ControllableProperty();
        powerControl.setValue("1");
        powerControl.setProperty(PJLinkConstants.POWER_PROPERTY);
        pjLinkCommunicator.controlProperty(powerControl);

        ArgumentCaptor<byte[]> command = ArgumentCaptor.forClass(byte[].class);
        verify(pjLinkCommunicator, Mockito.atLeastOnce()).send(command.capture());
        Assertions.assertEquals(new String(powerOnAuthCmd), new String(command.getValue()));
    }

    @Test
    public void testControlInputNoAuth() throws Exception {
        pjLinkCommunicator.internalInit();
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setConnectionState(ConnectionState.Unknown);
        when(pjLinkCommunicator.getConnectionStatus()).thenReturn(connectionStatus);
        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).then(invocationOnMock -> {
            pjLinkCommunicator.getConnectionStatus().setConnectionState(ConnectionState.Connected);
            return new byte[]{};
        });
        byte[] inptCommand = PJLinkCommand.INPT_CMD.getValue().clone();
        inptCommand[7] = 0x31;
        inptCommand[8] = 0x31;
        when(pjLinkCommunicator.send(inptCommand)).thenReturn(new byte[]{0x25, 0x31, 0x49, 0x4e, 0x50, 0x54, 0x3d, 0x4f, 0x4b, 0x0d});

        pjLinkCommunicator.getMultipleStatistics();
        ControllableProperty powerControl = new ControllableProperty();
        powerControl.setValue("COMPUTER");
        powerControl.setProperty(INPUT_PROPERTY);
        pjLinkCommunicator.controlProperty(powerControl);

        ArgumentCaptor<byte[]> command = ArgumentCaptor.forClass(byte[].class);
        verify(pjLinkCommunicator, Mockito.atLeastOnce()).send(command.capture());
        Assertions.assertEquals(new String(inptCommand), new String(command.getValue()));
    }

    @Test
    public void testControlVideoMuteNoAuth() throws Exception {
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setConnectionState(ConnectionState.Unknown);
        when(pjLinkCommunicator.getConnectionStatus()).thenReturn(connectionStatus);
        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).then(invocationOnMock -> {
            pjLinkCommunicator.getConnectionStatus().setConnectionState(ConnectionState.Connected);
            return new byte[]{};
        });
        byte[] videoMute = PJLinkCommand.VIDEO_MUTE_CMD.getValue().clone();
        videoMute[8] = 0x31;
        when(pjLinkCommunicator.send(videoMute)).thenReturn(new byte[]{0x25, 0x31, 0x41, 0x56, 0x4d, 0x54, 0x3d, 0x4f, 0x4b, 0x0d});

        ControllableProperty powerControl = new ControllableProperty();
        powerControl.setValue("1");
        powerControl.setProperty(PJLinkConstants.VIDEOMUTE_PROPERTY);
        pjLinkCommunicator.controlProperty(powerControl);

        ArgumentCaptor<byte[]> command = ArgumentCaptor.forClass(byte[].class);
        verify(pjLinkCommunicator, Mockito.atLeastOnce()).send(command.capture());
        Assertions.assertEquals(new String(videoMute), new String(command.getValue()));
    }

    @Test
    public void testControlAudioMuteNoAuth() throws Exception {
        ConnectionStatus connectionStatus = new ConnectionStatus();
        connectionStatus.setConnectionState(ConnectionState.Unknown);
        when(pjLinkCommunicator.getConnectionStatus()).thenReturn(connectionStatus);
        when(pjLinkCommunicator.send(PJLinkCommand.BLANK.getValue())).then(invocationOnMock -> {
            pjLinkCommunicator.getConnectionStatus().setConnectionState(ConnectionState.Connected);
            return new byte[]{};
        });
        byte[] audioMute = PJLinkCommand.AUDIO_MUTE_CMD.getValue().clone();
        audioMute[8] = 0x31;
        when(pjLinkCommunicator.send(audioMute)).thenReturn(new byte[]{0x25, 0x31, 0x41, 0x56, 0x4d, 0x54, 0x3d, 0x4f, 0x4b, 0x0d});

        ControllableProperty powerControl = new ControllableProperty();
        powerControl.setValue("1");
        powerControl.setProperty(PJLinkConstants.AUDIOMUTE_PROPERTY);
        pjLinkCommunicator.controlProperty(powerControl);

        ArgumentCaptor<byte[]> command = ArgumentCaptor.forClass(byte[].class);
        verify(pjLinkCommunicator, Mockito.atLeastOnce()).send(command.capture());
        Assertions.assertEquals(new String(audioMute), new String(command.getValue()));
    }
}
