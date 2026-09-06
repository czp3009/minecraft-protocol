package net.minecraft.network.protocol;

import java.util.List;

public final class FixturePacketTypes {
    public static final PacketType<ClientboundFixturePacket> CLIENTBOUND_FIXTURE = createClientbound("fixture");

    private static <T> PacketType<T> createClientbound(String name) {
        return new PacketType<>();
    }
}

final class PacketType<T> {}

class BasePacket {
    public int id;
}

final class ClientboundFixturePacket extends BasePacket {
    public String message;
    public List<Entry> entries;

    public String readMessage() {
        return message;
    }

    public record Entry(String name, int count) {}
}
