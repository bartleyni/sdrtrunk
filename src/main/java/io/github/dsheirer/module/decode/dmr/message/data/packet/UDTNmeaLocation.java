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

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.location.LocationIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRLocation;
import org.jdesktop.swingx.mapviewer.GeoPosition;

/**
 * Unified Data Transport (UDT) NMEA location coded short data payload (UDT format 5, ETSI TS 102 361-4).  This is
 * the ETSI standard GPS position format used by many Tier II radios (e.g. for DMR APRS position reports).
 *
 * The short format (single appended block) carries the time in 10-second resolution.  The long format (two
 * appended blocks) carries the time in 1-second resolution and adds the course over ground.
 */
public class UDTNmeaLocation
{
    private static final int ENCRYPTED_FLAG = 0;
    private static final int NORTH_FLAG = 1;
    private static final int EAST_FLAG = 2;
    private static final int QUALITY_FLAG = 3;
    private static final IntField SPEED_KNOTS = IntField.range(4, 10);
    private static final IntField LATITUDE_DEGREES = IntField.range(11, 17);
    private static final IntField LATITUDE_MINUTES = IntField.range(18, 23);
    private static final IntField LATITUDE_MINUTES_FRACTION = IntField.range(24, 37);
    private static final IntField LONGITUDE_DEGREES = IntField.range(38, 45);
    private static final IntField LONGITUDE_MINUTES = IntField.range(46, 51);
    private static final IntField LONGITUDE_MINUTES_FRACTION = IntField.range(52, 65);
    private static final IntField UTC_HOURS = IntField.range(66, 70);
    private static final IntField UTC_MINUTES = IntField.range(71, 76);
    private static final IntField UTC_SECONDS_SHORT = IntField.range(77, 79);
    private static final IntField UTC_SECONDS_LONG = IntField.range(77, 82);
    private static final IntField COURSE_OVER_GROUND = IntField.range(103, 111);
    private static final int SHORT_FORMAT_LENGTH = 80;
    private static final int LONG_FORMAT_LENGTH = 112;

    private final BinaryMessage mPayload;

    /**
     * Constructs an instance
     * @param payload from the UDT data blocks, starting at the first bit of the first appended block.
     */
    public UDTNmeaLocation(BinaryMessage payload)
    {
        mPayload = payload;
    }

    /**
     * Indicates if the payload is long enough to contain the short format.
     */
    private boolean hasShortFormat()
    {
        return mPayload != null && mPayload.size() >= SHORT_FORMAT_LENGTH;
    }

    /**
     * Indicates if the payload contains the long format with 1-second time resolution and course over ground.
     */
    public boolean isLongFormat()
    {
        return mPayload != null && mPayload.size() >= LONG_FORMAT_LENGTH;
    }

    /**
     * Indicates if the location payload is encrypted.
     */
    public boolean isEncrypted()
    {
        return hasShortFormat() && mPayload.get(ENCRYPTED_FLAG);
    }

    /**
     * Indicates if the payload contains a decodable, in-range position.
     */
    public boolean isValid()
    {
        return hasShortFormat() && !isEncrypted() &&
                mPayload.getInt(LATITUDE_DEGREES) <= 90 &&
                mPayload.getInt(LATITUDE_MINUTES) <= 59 &&
                mPayload.getInt(LATITUDE_MINUTES_FRACTION) <= 9999 &&
                mPayload.getInt(LONGITUDE_DEGREES) <= 180 &&
                mPayload.getInt(LONGITUDE_MINUTES) <= 59 &&
                mPayload.getInt(LONGITUDE_MINUTES_FRACTION) <= 9999 &&
                mPayload.getInt(UTC_HOURS) <= 23 &&
                mPayload.getInt(UTC_MINUTES) <= 59 &&
                (getLatitude() != 0.0 || getLongitude() != 0.0);
    }

    /**
     * Latitude in decimal degrees, negative for southern hemisphere.
     */
    public double getLatitude()
    {
        double latitude = mPayload.getInt(LATITUDE_DEGREES) + (mPayload.getInt(LATITUDE_MINUTES) / 60.0) +
                (mPayload.getInt(LATITUDE_MINUTES_FRACTION) / 600000.0);
        return mPayload.get(NORTH_FLAG) ? latitude : -latitude;
    }

    /**
     * Longitude in decimal degrees, negative for western hemisphere.
     */
    public double getLongitude()
    {
        double longitude = mPayload.getInt(LONGITUDE_DEGREES) + (mPayload.getInt(LONGITUDE_MINUTES) / 60.0) +
                (mPayload.getInt(LONGITUDE_MINUTES_FRACTION) / 600000.0);
        return mPayload.get(EAST_FLAG) ? longitude : -longitude;
    }

    /**
     * Speed in knots
     */
    public int getSpeedKnots()
    {
        return mPayload.getInt(SPEED_KNOTS);
    }

    /**
     * Course over ground in degrees (long format only), or -1 when not available.
     */
    public int getCourseOverGround()
    {
        return isLongFormat() ? mPayload.getInt(COURSE_OVER_GROUND) : -1;
    }

    /**
     * Quality indicator flag
     */
    public boolean getQuality()
    {
        return mPayload.get(QUALITY_FLAG);
    }

    /**
     * UTC time of the position fix formatted as HH:MM:SS
     */
    public String getUTCTime()
    {
        int seconds = isLongFormat() ? mPayload.getInt(UTC_SECONDS_LONG) : mPayload.getInt(UTC_SECONDS_SHORT) * 10;
        return String.format("%02d:%02d:%02d", mPayload.getInt(UTC_HOURS), mPayload.getInt(UTC_MINUTES), seconds);
    }

    /**
     * Geo-position for plotting on the map
     */
    public GeoPosition getPosition()
    {
        return new GeoPosition(getLatitude(), getLongitude());
    }

    /**
     * Location identifier
     */
    public LocationIdentifier getLocation()
    {
        return DMRLocation.createFrom(getLatitude(), getLongitude());
    }

    @Override
    public String toString()
    {
        if(!hasShortFormat())
        {
            return "NMEA LOCATION (insufficient data)";
        }

        if(isEncrypted())
        {
            return "NMEA LOCATION (encrypted)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("LOCATION:").append(String.format("%.5f %.5f", getLatitude(), getLongitude()));
        sb.append(" SPEED:").append(getSpeedKnots()).append("KTS");

        if(isLongFormat())
        {
            sb.append(" HEADING:").append(getCourseOverGround());
        }

        sb.append(" UTC:").append(getUTCTime());

        if(!isValid())
        {
            sb.append(" (INVALID)");
        }

        return sb.toString();
    }
}
