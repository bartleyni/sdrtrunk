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

package io.github.dsheirer.module.decode.dmr.message.data.packet;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UDTNmeaLocationTest
{
    /**
     * Creates a UDT NMEA location payload: 40 26.7717 N, 79 58.1234 W, 10 knots, 13:45:27 UTC, course 270.
     * @param blocks count of appended 96-bit data blocks (1 = short format, 2 = long format)
     */
    private static CorrectedBinaryMessage payload(int blocks)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(96 * blocks);
        message.load(1, 1, 1);        //North
        message.load(2, 1, 0);        //West
        message.load(3, 1, 1);        //Quality
        message.load(4, 7, 10);       //Speed knots
        message.load(11, 7, 40);      //Latitude degrees
        message.load(18, 6, 26);      //Latitude minutes
        message.load(24, 14, 7717);   //Latitude minutes fraction
        message.load(38, 8, 79);      //Longitude degrees
        message.load(46, 6, 58);      //Longitude minutes
        message.load(52, 14, 1234);   //Longitude minutes fraction
        message.load(66, 5, 13);      //UTC hours
        message.load(71, 6, 45);      //UTC minutes

        if(blocks > 1)
        {
            message.load(77, 6, 27);  //UTC seconds (1-second resolution)
            message.load(103, 9, 270);//Course over ground
        }
        else
        {
            message.load(77, 3, 2);   //UTC seconds (10-second resolution)
        }

        return message;
    }

    @Test
    void longFormat()
    {
        UDTNmeaLocation location = new UDTNmeaLocation(payload(2));
        assertTrue(location.isValid());
        assertTrue(location.isLongFormat());
        assertEquals(40.0 + 26.7717 / 60.0, location.getLatitude(), 0.000001);
        assertEquals(-(79.0 + 58.1234 / 60.0), location.getLongitude(), 0.000001);
        assertEquals(10, location.getSpeedKnots());
        assertEquals(270, location.getCourseOverGround());
        assertEquals("13:45:27", location.getUTCTime());
    }

    @Test
    void shortFormat()
    {
        UDTNmeaLocation location = new UDTNmeaLocation(payload(1));
        assertTrue(location.isValid());
        assertFalse(location.isLongFormat());
        assertEquals(-1, location.getCourseOverGround());
        assertEquals("13:45:20", location.getUTCTime());
    }

    @Test
    void encryptedIsInvalid()
    {
        CorrectedBinaryMessage message = payload(2);
        message.load(0, 1, 1);
        UDTNmeaLocation location = new UDTNmeaLocation(message);
        assertTrue(location.isEncrypted());
        assertFalse(location.isValid());
    }

    @Test
    void outOfRangeIsInvalid()
    {
        CorrectedBinaryMessage message = payload(2);
        message.load(18, 6, 63); //Latitude minutes > 59
        assertFalse(new UDTNmeaLocation(message).isValid());
    }
}
