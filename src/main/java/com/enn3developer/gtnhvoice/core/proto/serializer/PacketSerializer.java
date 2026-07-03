/*
 * Adapted from Plasmo Voice (su.plo.voice), licensed under LGPL-3.0.
 * See THIRD-PARTY-NOTICES.md for details.
 * Ported from Kotlin (su.plo.voice.proto.serializer.PacketSerializer).
 */
package com.enn3developer.gtnhvoice.core.proto.serializer;

import java.io.IOException;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;

/**
 * The PacketSerializer interface defines the contract for objects that can be serialized
 * and deserialized to and from byte streams using a {@link ByteArrayDataInput} for deserialization
 * and a {@link ByteArrayDataOutput} for serialization. Implementing classes must provide methods
 * to serialize objects of type {@code T} into a byte stream and deserialize objects of type
 * {@code T} from a byte stream.
 *
 * @param <T> The type of object to be serialized and deserialized.
 */
public interface PacketSerializer<T> {

    /**
     * Deserialize an object of type {@code T} from a byte stream using the provided {@link ByteArrayDataInput}.
     * This method reconstructs the object from its serialized form.
     *
     * @param buffer The {@link ByteArrayDataInput} containing the serialized data.
     * @return The deserialized object of type {@code T}.
     * @throws IOException If an I/O error occurs during deserialization.
     */
    T deserialize(ByteArrayDataInput buffer) throws IOException;

    /**
     * Serialize an object of type {@code T} to a byte stream using the provided {@link ByteArrayDataOutput}.
     * This method converts the object into its serialized form.
     *
     * @param obj    The object of type {@code T} to be serialized.
     * @param buffer The {@link ByteArrayDataOutput} to which the serialized data should be written.
     * @throws IOException If an I/O error occurs during serialization.
     */
    void serialize(T obj, ByteArrayDataOutput buffer) throws IOException;
}
