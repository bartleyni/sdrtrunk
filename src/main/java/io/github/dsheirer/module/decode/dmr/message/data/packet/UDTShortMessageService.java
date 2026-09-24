/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.dmr.message.DMRMessage;
import io.github.dsheirer.module.decode.dmr.message.data.header.UDTHeader;
import io.github.dsheirer.module.decode.dmr.message.type.UnifiedDataTransportFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Short Message Service (SMS) using Unified Data Transport
 */
public class UDTShortMessageService extends DMRMessage
{
    private static final Logger LOGGER = LoggerFactory.getLogger(UDTShortMessageService.class);
    private UDTHeader mHeader;
    private String mSMS;
    private UDTNmeaLocation mNmeaLocation;
    private Boolean mCrcValid;
    private int mValidatedBlockCount;
    private boolean mIdsVerifiedByPreamble = false;
    private boolean mHeaderRecovered = false;
    private static final int UDT_BLOCK_LENGTH = 96;
    private static final int CRC_LENGTH = 16;
    private static final int CRC_CCITT_POLYNOMIAL = 0x1021;

    /**
     * Constructs an instance
     *
     * @param header for the sequence
     * @param payload from the data blocks
     */
    public UDTShortMessageService(UDTHeader header, CorrectedBinaryMessage payload)
    {
        super(payload, header.getTimestamp(), header.getTimeslot());
        mHeader = header;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        return mHeader.getIdentifiers();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("CC:").append(mHeader.getSlotType().getColorCode());
        sb.append(" SMS MESSAGE:").append(getSMS());

        if(isHeaderRecovered())
        {
            sb.append(" [HEADER RECOVERED]");
        }
        else if(!isHeaderValid())
        {
            sb.append(" [HEADER CRC ERROR]");
        }
        sb.append(" FROM:").append(mHeader.getSourceLLID());
        sb.append(" TO:").append(mHeader.getDestinationLLID());
        sb.append(" HEX:").append(getMessage().toHexString());
        return sb.toString();
    }

    public String getSMS()
    {
        if(mSMS == null)
        {
            switch(mHeader.getFormat())
            {
                case UNICODE_16:
                    mSMS = parseUnicodePayload();
                    break;
                case ASCII_7:
                    mSMS = parseISO7Payload();
                    break;
                case ASCII_8:
                    mSMS = parseISO8Payload();
                    break;
                case BCD_4_BITS:
                    mSMS = parseBCD4Payload();
                    break;
                case NMEA_GPS_LOCATION_CODED:
                    mSMS = isCrcValid() ? getNmeaLocation().toString() : "NMEA LOCATION (CRC ERROR)";
                    break;
                case BINARY:
                case MOBILE_SUBSCRIBER_OR_TALKGROUP_ADDRESS:
                case IP_ADDRESS:
                case VENDOR_PROPRIETARY_8:
                case VENDOR_PROPRIETARY_9:
                case MIXED_FORMAT:
                case UNKNOWN:
//                    LOGGER.warn("Unrecognized UDT Short Data Format: " + mHeader.getFormat() +
//                            " - Please send this to the sdrtrunk developer - Hex:" + getMessage().toHexString() +
//                            " As String:" + new String(getMessage().getBytes()));
                    mSMS = "Error:" + new String(getMessage().getBytes());
                    break;
            }
        }

        if(mSMS == null)
        {
            mSMS = "error - unrecognized format: " + mHeader.getFormat();
        }

        return mSMS;
    }

    /**
     * Indicates if the UDT payload passes its CRC check.  The final 16 bits of the last appended block carry a
     * CRC-CCITT (initial value 0, no mask) calculated over all preceding payload bits.  The payload CRC is always
     * checked, independent of the channel's Ignore CRC setting, because a failed check means corrupted content.
     */
    public boolean isCrcValid()
    {
        if(mCrcValid == null)
        {
            mCrcValid = checkCrc();
        }

        return mCrcValid;
    }

    private boolean checkCrc()
    {
        CorrectedBinaryMessage payload = getMessage();

        if(payload == null)
        {
            return false;
        }

        int claimedBlocks = mHeader.getAppendedBlockCount();

        if(checkCrc(payload, claimedBlocks))
        {
            mValidatedBlockCount = claimedBlocks;
            return true;
        }

        //The header's appended block count may have been corrupted (e.g. header CRC error) - validate against the
        //quantity of blocks that were actually received.  The payload CRC still protects the content.
        int receivedBlocks = payload.size() / UDT_BLOCK_LENGTH;

        if(receivedBlocks >= 1 && receivedBlocks != claimedBlocks && checkCrc(payload, receivedBlocks))
        {
            mValidatedBlockCount = receivedBlocks;
            return true;
        }

        return false;
    }

    /**
     * Checks the payload CRC assuming the specified quantity of appended blocks.
     */
    private static boolean checkCrc(CorrectedBinaryMessage payload, int blocks)
    {
        int length = blocks * UDT_BLOCK_LENGTH;

        if(blocks < 1 || payload.size() < length)
        {
            return false;
        }

        int dataLength = length - CRC_LENGTH;
        int crc = 0;

        for(int i = 0; i < dataLength; i++)
        {
            boolean feedback = payload.get(i) ^ ((crc & 0x8000) != 0);
            crc = (crc << 1) & 0xFFFF;

            if(feedback)
            {
                crc ^= CRC_CCITT_POLYNOMIAL;
            }
        }

        return crc == payload.getInt(IntField.range(dataLength, length - 1));
    }

    /**
     * Indicates if the UDT header passed its own CRC check.  When false, the source and destination IDs may be wrong
     * even though the payload CRC is valid.
     */
    public boolean isHeaderValid()
    {
        return mHeader.isValid() || mIdsVerifiedByPreamble;
    }

    /**
     * Marks the source and destination IDs as verified by a CRC valid preamble from the same packet sequence.
     */
    public void setIdsVerifiedByPreamble(boolean verified)
    {
        mIdsVerifiedByPreamble = verified;
    }

    /**
     * Indicates that the original UDT header was lost and this message was reconstructed from the packet sequence's
     * preamble and the source radio's previously used UDT format.
     */
    public boolean isHeaderRecovered()
    {
        return mHeaderRecovered;
    }

    /**
     * Sets the header recovered flag.
     */
    public void setHeaderRecovered(boolean recovered)
    {
        mHeaderRecovered = recovered;
    }

    /**
     * Source radio ID from the header
     */
    public int getSourceId()
    {
        return mHeader.getSourceLLID().getValue();
    }

    /**
     * Raw UDT format value from the header
     */
    public int getFormatValue()
    {
        return mHeader.getFormatValue();
    }

    /**
     * Indicates if this short data message carries an NMEA coded GPS location (e.g. a DMR APRS position report).
     */
    public boolean isNmeaLocation()
    {
        return mHeader.getFormat() == UnifiedDataTransportFormat.NMEA_GPS_LOCATION_CODED;
    }

    /**
     * NMEA coded GPS location parsed from the payload.  Only meaningful when isNmeaLocation() is true.
     */
    public UDTNmeaLocation getNmeaLocation()
    {
        if(mNmeaLocation == null)
        {
            //Limit the payload to the CRC validated block count so that short/long format detection is correct
            if(isCrcValid() && getMessage().size() > mValidatedBlockCount * UDT_BLOCK_LENGTH)
            {
                mNmeaLocation = new UDTNmeaLocation(getMessage().getSubMessage(0, mValidatedBlockCount * UDT_BLOCK_LENGTH));
            }
            else
            {
                mNmeaLocation = new UDTNmeaLocation(getMessage());
            }
        }

        return mNmeaLocation;
    }

    /**
     * Parses a unicode 16-bit payload from the message.
     * @return parsed message
     */
    private String parseUnicodePayload()
    {
        int length = getMessage().size();
        length -= (mHeader.getPadNibbleCount() * 4);
        length -= 16; //Exclude the dangling 32-bit CRC
        length -= 16; //Exclude the dangling 0x0006 ACKNOWLEDGE character

        if(length > 16)
        {
            return getMessage().parseUnicode(0, (length / 16));
        }
        else
        {
            return "(insufficient data)";
        }
    }

    /**
     * Parses an ISO-7 payload from the message.
     * @return parsed message
     */
    private String parseISO7Payload()
    {
        int length = getMessage().size();
        length -= (mHeader.getPadNibbleCount() * 4);

        if(length > 7)
        {
            return getMessage().parseISO7(0, (length / 7));
        }
        else
        {
            return "(insufficient data)";
        }
    }

    /**
     * Parses an ISO-8 payload from the message.
     * @return parsed message
     */
    private String parseISO8Payload()
    {
        int length = getMessage().size();
        length -= (mHeader.getPadNibbleCount() * 4);

        if(length > 8)
        {
            return getMessage().parseISO8(0, (length / 8));
        }
        else
        {
            return "(insufficient data)";
        }
    }

    /**
     * Parses a BCD 4-bit payload from the message.
     * @return parsed message
     */
    private String parseBCD4Payload()
    {
        int length = getMessage().size();
        length -= (mHeader.getPadNibbleCount() * 4);

        if(length > 4)
        {
            return getMessage().parseBCD4(0, (length / 4));
        }
        else
        {
            return "(insufficient data)";
        }
    }
}
