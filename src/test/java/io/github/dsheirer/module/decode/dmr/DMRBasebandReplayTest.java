/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.dmr;

import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.dmr.message.data.packet.UDTShortMessageService;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.wave.ComplexWaveSource;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Iterator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Replays a DMR baseband recording (sdrtrunk channel baseband WAV) through the DMR decoder and writes each decoded
 * message, with its position in the recording, to build/dmr-replay.txt.  Skipped unless the recording path is supplied
 * via the DMR_REPLAY_FILE environment variable.  Used to diagnose on-air decoding issues offline.
 */
public class DMRBasebandReplayTest
{
    private static final int FRAMES_PER_READ = 2048;

    @Test
    void replay() throws Exception
    {
        String path = System.getenv("DMR_REPLAY_FILE");
        assumeTrue(path != null && new File(path).exists(), "DMR_REPLAY_FILE not set - skipping replay");

        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setSimplexMode(!"false".equalsIgnoreCase(System.getenv("DMR_REPLAY_SIMPLEX")));
        DMRDecoder decoder = new DMRDecoder(config, false);
        decoder.start();

        long[] framesRead = {0};
        double[] sampleRate = {50000};
        int[] gpsReports = {0};

        try(PrintWriter out = new PrintWriter(new File("build/dmr-replay.txt"));
            ComplexWaveSource source = new ComplexWaveSource(new File(path), false))
        {
            decoder.setMessageListener((IMessage message) -> {
                if(message instanceof SyncLossMessage sync && sync.getBitsProcessed() >= 4800)
                {
                    return; //Suppress idle sync loss noise
                }

                if(message instanceof UDTShortMessageService sms && sms.isNmeaLocation() && sms.isCrcValid())
                {
                    gpsReports[0]++;
                }

                out.printf("%8.3f %s %s%n", framesRead[0] / sampleRate[0], message.isValid() ? "OK " : "BAD",
                        message.toString());
            });

            source.setListener(buffer -> {
                Iterator<ComplexSamples> it = buffer.iterator();

                while(it.hasNext())
                {
                    decoder.receive(it.next());
                }
            });
            source.start();
            sampleRate[0] = source.getSampleRate();
            decoder.setSampleRate(sampleRate[0]);

            try
            {
                while(true)
                {
                    source.next(FRAMES_PER_READ, true);
                    framesRead[0] += FRAMES_PER_READ;
                }
            }
            catch(IOException endOfFile)
            {
                //End of recording
            }

            out.printf("GPS REPORTS (CRC VALID): %d%n", gpsReports[0]);
        }

        System.out.println("DMR REPLAY: GPS REPORTS (CRC VALID): " + gpsReports[0]);
    }
}
