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

package io.github.dsheirer.module.decode.ip.mototrbo.tms;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.ip.IPacket;
import io.github.dsheirer.module.decode.ip.Packet;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Text Messaging Service (TMS) message packet.
 */
public class TMSPacket extends Packet
{
    private TMSHeader mHeader;

    /**
     * Constructs a parser for a header contained within a binary message starting at the offset.
     *
     * @param message containing the packet
     * @param offset to the packet within the message
     */
    public TMSPacket(CorrectedBinaryMessage message, int offset)
    {
        super(message, offset);
    }

    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("TEXT MESSAGE: ").append(getTextMessage());
        return sb.toString();
    }

    /**
     * Extracts the text message payload.
     *
     * Supports two TMS layouts:
     * 1. Binary TMS header: 16-bit length (count of bytes that follow), a header byte (bit 5 = extension), an
     *    address length byte and optional address, then extension bytes (bit 7 = another extension byte follows),
     *    followed by UTF-16LE text.  Used by Motorola and compatible radios (e.g. Ailunce HD1/HD2).
     * 2. Legacy layout: 4 ASCII digit characters followed by UTF-16LE text.
     */
    public String getTextMessage()
    {
        String binaryHeaderText = getBinaryHeaderTextMessage();

        if(binaryHeaderText != null)
        {
            return binaryHeaderText;
        }

        return getLegacyTextMessage();
    }

    /**
     * Parses the text using the binary TMS header layout.
     * @return text or null if the payload doesn't conform to the binary header layout.
     */
    private String getBinaryHeaderTextMessage()
    {
        int available = (getMessage().size() - getOffset()) / 8;

        if(available < 4)
        {
            return null;
        }

        int length = ((getByte(0) & 0xFF) << 8) | (getByte(1) & 0xFF);
        int end = 2 + length;

        //Legacy layout starts with ASCII digits, which produce an implausibly large binary length
        if(length < 2 || end > available)
        {
            return null;
        }

        int pointer = 2;
        int header = getByte(pointer++) & 0xFF;

        if((header & 0x20) != 0) //Extension - address and extension bytes follow
        {
            int addressLength = getByte(pointer++) & 0xFF;
            pointer += addressLength;

            //Header extension bytes: continue while bit 7 is set
            int extension;
            do
            {
                if(pointer >= end)
                {
                    return null;
                }

                extension = getByte(pointer++) & 0xFF;
            }
            while((extension & 0x80) != 0);
        }

        int textLength = end - pointer;

        if(textLength <= 0 || (textLength % 2) != 0)
        {
            return null;
        }

        byte[] text = new byte[textLength];

        for(int x = 0; x < textLength; x++)
        {
            text[x] = getByte(pointer + x);
        }

        return clean(new String(text, StandardCharsets.UTF_16LE));
    }

    /**
     * Byte at the specified byte index relative to the packet offset.
     */
    private byte getByte(int index)
    {
        return getMessage().getByte(getOffset() + (index * 8));
    }

    /**
     * Removes leading/trailing line breaks and control characters that radios prepend to message text.
     */
    private static String clean(String text)
    {
        return text.replaceAll("^[\\r\\n\\u0000]+|[\\r\\n\\u0000]+$", "");
    }

    /**
     * Parses the text using the legacy layout (4 ASCII digits followed by UTF-16LE text).
     */
    private String getLegacyTextMessage()
    {
        //Payload starts at packet offset, after 32-bit header
        int byteCount = (getMessage().length() - getOffset() - 32) / 8;

        //Use the header's character count when it is plausible, so that trailing bytes (e.g. packet CRC) are
        //excluded and a trailing zero byte of the final character isn't truncated.
        int characterCount = getHeader().getCharacterCount();

        if(characterCount > 0 && (32 + characterCount * 16) <= (getMessage().size() - getOffset()))
        {
            byteCount = characterCount * 2;
        }

        if(byteCount > 0)
        {
            byte[] message = new byte[byteCount];

            for(int x = 0; x < byteCount; x++)
            {
                message[x] = getMessage().getByte(getOffset() + 32 + (x * 8));
            }

            //Characters are 16-bit, UTF-16 Little Endian
            return new String(message, StandardCharsets.UTF_16LE);
        }

        return "(ERROR-INSUFFICIENT BYTES)";
    }


    @Override
    public TMSHeader getHeader()
    {
        if(mHeader == null)
        {
            mHeader = new TMSHeader(getMessage(), getOffset());
        }

        return mHeader;
    }

    @Override
    public IPacket getPayload()
    {
        //There is no child payload.
        return null;
    }

    @Override
    public List<Identifier> getIdentifiers()
    {
        //Massage and Header don't have any additional identifiers.
        return Collections.emptyList();
    }
}
