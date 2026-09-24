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

package io.github.dsheirer.module.decode.ip.mototrbo.tms;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.ip.IPacket;
import io.github.dsheirer.module.decode.ip.ipv4.IPV4Packet;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TMSPacketTest
{
    /**
     * Reassembled unconfirmed data packet (4 rate 1/2 blocks, including the trailing 32-bit packet CRC) captured from
     * an Ailunce HD2 (radio 908 to 2345) sending the text "!ad" as a TMS message over IP/UDP port 4007.
     */
    private static final String HD2_TEXT_PACKET = "4500002C000F0000401155FE0C00038C0C0009290FA70FA7" +
            "00184EA7000EE00090040D000A002100610064008BD2B092";

    private static CorrectedBinaryMessage fromHex(String hex)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(hex.length() * 4);

        for(int x = 0; x < hex.length() / 2; x++)
        {
            message.load(x * 8, 8, Integer.parseInt(hex.substring(x * 2, x * 2 + 2), 16));
        }

        return message;
    }

    @Test
    void decodesCapturedHd2TextMessage()
    {
        IPV4Packet ip = new IPV4Packet(fromHex(HD2_TEXT_PACKET), 0);
        IPacket udp = ip.getPayload();
        assertTrue(udp != null && udp.getPayload() instanceof TMSPacket, "Expected a TMS packet on UDP port 4007");
        TMSPacket tms = (TMSPacket)udp.getPayload();
        assertEquals("!ad", tms.getTextMessage());
    }

    @Test
    void decodesLegacyLayout()
    {
        //Legacy layout: 4 ASCII digits followed by UTF-16LE text
        byte[] text = "hello".getBytes(StandardCharsets.UTF_16LE);
        StringBuilder hex = new StringBuilder();

        for(byte b: "0005".getBytes(StandardCharsets.US_ASCII))
        {
            hex.append(String.format("%02X", b));
        }

        for(byte b: text)
        {
            hex.append(String.format("%02X", b));
        }

        TMSPacket tms = new TMSPacket(fromHex(hex.toString()), 0);
        assertEquals("hello", tms.getTextMessage());
    }
}
