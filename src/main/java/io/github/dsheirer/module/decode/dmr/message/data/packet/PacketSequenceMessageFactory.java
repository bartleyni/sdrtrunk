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

import io.github.dsheirer.bits.BitSetFullException;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.dmr.message.data.block.DataBlock;
import io.github.dsheirer.module.decode.dmr.message.data.header.HeaderMessage;
import io.github.dsheirer.module.decode.dmr.message.data.header.PacketSequenceHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.UDTHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.hytera.HyteraDataEncryptionHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.motorola.MNISProprietaryDataHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.motorola.MotorolaDataEncryptionHeader;
import io.github.dsheirer.module.decode.dmr.message.type.ApplicationType;
import io.github.dsheirer.module.decode.dmr.message.type.DataPacketFormat;
import io.github.dsheirer.module.decode.dmr.message.type.ServiceAccessPoint;
import io.github.dsheirer.module.decode.ip.DefinedShortDataPacket;
import io.github.dsheirer.module.decode.ip.UnknownPacket;
import io.github.dsheirer.module.decode.ip.hytera.rrs.HyteraRrsPacket;
import io.github.dsheirer.module.decode.ip.hytera.sds.HyteraTokenHeader;
import io.github.dsheirer.module.decode.ip.hytera.sds.HyteraUnknownPacket;
import io.github.dsheirer.module.decode.ip.hytera.shortdata.HyteraShortDataPacket;
import io.github.dsheirer.module.decode.ip.hytera.sms.HyteraSmsPacket;
import io.github.dsheirer.module.decode.ip.ipv4.IPV4Header;
import io.github.dsheirer.module.decode.ip.ipv4.IPV4Packet;
import io.github.dsheirer.module.decode.ip.mototrbo.ars.ARSPacket;
import io.github.dsheirer.module.decode.ip.mototrbo.lrrp.LRRPPacket;
import io.github.dsheirer.module.decode.ip.mototrbo.xcmp.XCMPPacket;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts a DMR packet sequence into a message
 */
public class PacketSequenceMessageFactory
{
    private final static Logger mLog = LoggerFactory.getLogger(PacketSequenceMessageFactory.class);

    /**
     * Creates a message from a packet sequence
      * @param packetSequence with message parts
     * @return message
     */
    public static IMessage create(PacketSequence packetSequence)
    {
        if(packetSequence != null)
        {
            if(packetSequence.hasPacketSequenceHeader())
            {
                PacketSequenceHeader primaryHeader = packetSequence.getPacketSequenceHeader();
                CorrectedBinaryMessage packet = getPacket(packetSequence, primaryHeader.isConfirmedData());

                if(packet != null)
                {
                    IMessage message = switch(primaryHeader.getServiceAccessPoint())
                    {
                        case IP_PACKET_DATA -> createIPPacketData(packetSequence, packet);
                        case PROPRIETARY_DATA -> createProprietary(packetSequence, packet);
                        case SHORT_DATA -> createDefinedShortData(packetSequence, packet);
                        default -> new DMRPacketMessage(packetSequence, new UnknownPacket(packet, 0), packet,
                                packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
                    };

                    //Unconfirmed packets carry a trailing CRC-32 - flag packets with missing blocks or a failed CRC so
                    //that corrupted content (e.g. text messages, positions) isn't presented as valid.
                    if(message instanceof DMRPacketMessage packetMessage && !primaryHeader.isConfirmedData() &&
                            !packetSequence.hasProprietaryDataHeader())
                    {
                        packetMessage.setValid(packetSequence.isComplete() && isUnconfirmedPacketCrcValid(packet));
                    }

                    return message;
                }
            }
            else if(packetSequence.hasUDTHeader() && packetSequence.hasDataBlocks())
            {
                UDTHeader header = packetSequence.getUDTHeader();
                if(header.isShortData())
                {
                    CorrectedBinaryMessage payload = getUnconfirmedPayload(packetSequence.getDataBlocks());
                    return new UDTShortMessageService(header, payload);
                }
            }
        }

        return null;
    }

    /**
     * Validates the CRC-32 of an unconfirmed data packet.  The final 32 bits of the last data block carry a CRC-32
     * (polynomial 0x04C11DB7, initial value 0, not reflected) calculated over all preceding packet octets (including
     * pad octets) with each pair of octets swapped, transmitted least significant octet first.
     * @param packet containing the reassembled unconfirmed data blocks
     * @return true if the CRC is valid
     */
    public static boolean isUnconfirmedPacketCrcValid(CorrectedBinaryMessage packet)
    {
        if(packet == null || packet.size() < 64 || packet.size() % 16 != 0)
        {
            return false;
        }

        int octets = packet.size() / 8;
        int dataOctets = octets - 4;
        int crc = 0;

        for(int x = 0; x < dataOctets; x++)
        {
            //Octets are processed in swapped pairs
            int index = (x % 2 == 0) ? x + 1 : x - 1;
            int value = packet.getByte(index * 8) & 0xFF;
            crc ^= value << 24;

            for(int bit = 0; bit < 8; bit++)
            {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }

        int received = 0;

        for(int x = 0; x < 4; x++)
        {
            received |= (packet.getByte((dataOctets + x) * 8) & 0xFF) << (8 * x);
        }

        return crc == received;
    }

    /**
     * Assembles the unconfirmed payload bytes from each of the data blocks into a contiguous binary message.
     * @param dataBlocks to assemble
     * @return assembled unconfirmed payload.
     */
    public static CorrectedBinaryMessage getUnconfirmedPayload(List<DataBlock> dataBlocks)
    {
        if(!dataBlocks.isEmpty())
        {
            int length = 0;

            List<CorrectedBinaryMessage> fragments = new ArrayList<>();
            for(DataBlock dataBlock: dataBlocks)
            {
                CorrectedBinaryMessage fragment = dataBlock.getUnConfirmedPayload();
                length += fragment.size();
                fragments.add(fragment);
            }

            CorrectedBinaryMessage combined = new CorrectedBinaryMessage(length);
            int pointer = 0;
            for(CorrectedBinaryMessage fragment: fragments)
            {
                combined.load(pointer, fragment);
                pointer += fragment.size();
            }

            return combined;
        }

        return null;
    }

    /**
     * Creates a proprietary data message from the packet sequence
     * @param packetSequence containing proprietary message fragments
     * @param packet reassembled from the fragments
     * @return message
     */
    public static IMessage createProprietary(PacketSequence packetSequence, CorrectedBinaryMessage packet)
    {
        HeaderMessage secondaryHeader = packetSequence.getProprietaryDataHeader();

        //MotoTRBO MNIS
        if(secondaryHeader instanceof MNISProprietaryDataHeader)
        {
            ApplicationType applicationType = ((MNISProprietaryDataHeader)secondaryHeader).getApplicationType();

            switch(applicationType)
            {
                case AUTOMATIC_REGISTRATION_SERVICE:
                    return new DMRPacketMessage(packetSequence, new ARSPacket(packet, 0),
                        packet,packetSequence.getTimeslot(),
                        packetSequence.getPacketSequenceHeader().getTimestamp());
                case MNIS_LRRP:
                case LOCATION_REQUEST_RESPONSE_PROTOCOL:
                    return new DMRPacketMessage(packetSequence, new LRRPPacket(packet, 0), packet,
                        packetSequence.getTimeslot(),
                        packetSequence.getPacketSequenceHeader().getTimestamp());
                case EXTENSIBLE_COMMAND_MESSAGE_PROTOCOL:
                    return new DMRPacketMessage(packetSequence, new XCMPPacket(packet, 0), packet,
                        packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
                default:
                    mLog.info("Unknown MNIS Application Type: " + MNISProprietaryDataHeader.getApplicationTypeValue(packet));
                    return new DMRPacketMessage(packetSequence, new UnknownPacket(packet, 0), packet,
                        packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
            }
        }
        else if(secondaryHeader instanceof HyteraDataEncryptionHeader hdeh)
        {
            ServiceAccessPoint sap = hdeh.getServiceAccessPoint();

            switch(sap)
            {
                case SHORT_DATA:
                    return new DMRPacketMessage(packetSequence, new HyteraShortDataPacket(packetSequence, packet), packet,
                            packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
                case PROPRIETARY_DATA:
                    HyteraTokenHeader hyteraTokenHeader = new HyteraTokenHeader(packet);
                    if(hyteraTokenHeader.isSMSMessage())
                    {
                        return new DMRPacketMessage(packetSequence, new HyteraSmsPacket(hyteraTokenHeader), packet,
                                packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
                    }
                    else
                    {
                        return new DMRPacketMessage(packetSequence, new HyteraUnknownPacket(hyteraTokenHeader), packet,
                                packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
                    }
                default:
                    return new DMRPacketMessage(packetSequence, new UnknownPacket(packet, 0), packet,
                            packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
            }
        }
        else if(secondaryHeader instanceof MotorolaDataEncryptionHeader &&
                packetSequence.getPacketSequenceHeader().getDataPacketFormat() == DataPacketFormat.DEFINED_SHORT_DATA)
        {
            return createDefinedShortData(packetSequence, packet);
        }
        else
        {
            if(packetSequence.getProprietaryDataHeader() != null)
            {
//                mLog.info("Unknown Proprietary Packet Header Type - creating unknown packet. Data Packet Format: " +
//                        packetSequence.getPacketSequenceHeader().getDataPacketFormat() + " Proprietary Header: " +
//                        packetSequence.getProprietaryDataHeader().getClass());
            }

            return new DMRPacketMessage(packetSequence, new UnknownPacket(packet, 0), packet,
                packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
        }
    }

    /**
     * Creates an IP packet from the packet sequence
     * @param packetSequence containing IP packet fragments
     * @param packet that is reassembled from the fragments
     * @return message
     */
    public static IMessage createIPPacketData(PacketSequence packetSequence, CorrectedBinaryMessage packet)
    {
            int version = IPV4Header.getIPVersion(packet, 0);

            switch(version)
            {
                case 4:
                    return new DMRPacketMessage(packetSequence, new IPV4Packet(packet, 0), packet,
                        packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
                default:
//                    mLog.info("Unrecognized IP Packet Version: " + version + " - returning unknown packet");
                    return new DMRPacketMessage(packetSequence, new UnknownPacket(packet, 0), packet,
                        packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
            }
    }

    /**
     * Creates a Short Data packet from the packet sequence
     * @param packetSequence containin the short data sequence
     * @param packet with the reassembled contents
     * @return constructed DMR packet sequence message
     */
    public static IMessage createDefinedShortData(PacketSequence packetSequence, CorrectedBinaryMessage packet)
    {
        //Test to see if this is Hytera Radio Registration Service (RRS)
        HyteraTokenHeader hyteraTokenHeader = new HyteraTokenHeader(packet);

        if(hyteraTokenHeader.isRRSMessage())
        {
            return new DMRPacketMessage(packetSequence, new HyteraRrsPacket(hyteraTokenHeader), packet,
                    packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
        }
        else
        {
            return new DMRPacketMessage(packetSequence, new DefinedShortDataPacket(packet, 0), packet,
                    packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
        }
    }

    /**
     * Extracts and reassembles the packet contents from a Confirmed or Unconfirmed packet sequence.
     * @param sequence that contains a Header and Data Blocks
     * @param confirmed is true or unconfirmed is false
     * @return the extracted packet or null.
     */
    public static CorrectedBinaryMessage getPacket(PacketSequence sequence, boolean confirmed)
    {
        List<CorrectedBinaryMessage> fragments = new ArrayList<>();
        int length = 0;

        if(sequence.hasProprietaryDataHeader())
        {
            CorrectedBinaryMessage prefix = sequence.getProprietaryDataHeader().getPacketPrefix();

            if(prefix != null)
            {
                fragments.add(prefix);
                length += prefix.size();
            }
        }

        for(DataBlock dataBlock: sequence.getDataBlocks())
        {
            //TODO: check data block sequence numbers for confirmed payloads
            CorrectedBinaryMessage fragment = (confirmed ? dataBlock.getConfirmedPayload() : dataBlock.getUnConfirmedPayload());
            fragments.add(fragment);
            length += fragment.size();
        }

        if(length > 0)
        {
            CorrectedBinaryMessage packet = new CorrectedBinaryMessage(length);

            for(CorrectedBinaryMessage fragment: fragments)
            {
                for(int x = 0; x < fragment.size(); x++)
                {
                    try
                    {
                        packet.add(fragment.get(x));
                    }
                    catch(BitSetFullException bsfe)
                    {
                        //We should never get here
                        mLog.error("BitSet full while assembling packet fragments");
                        return packet;
                    }
                }
            }

//            mLog.info("Packet Bytes:" + packet.size() + " MSG:" + packet.toHexString());

            return packet;
        }

        return null;
    }

    /**
     * Inspects the first 7 bits of each data block to see if a contiguous data block sequence number pattern is
     * present.  This might indicate that the payload is a confirmed payload versus an unconfirmed payload when
     * the packet sequence is not yet identified.
     *
     * @param dataBlocks to inspect.
     * @return true if each of the data blocks have a sequential data block sequencing number.
     */
    public static boolean isConfirmedDataBlocks(List<DataBlock> dataBlocks)
    {
        int sequenceNumber = 0;

        for(DataBlock dataBlock: dataBlocks)
        {
            if(dataBlock.getDataBlockSerialNumber() == sequenceNumber)
            {
                sequenceNumber++;
            }
            else
            {
                return false;
            }
        }

        return true;
    }
}
