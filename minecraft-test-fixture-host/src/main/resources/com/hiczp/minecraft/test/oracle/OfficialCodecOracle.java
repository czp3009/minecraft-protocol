package com.hiczp.minecraft.test.oracle;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;

/**
 * Executes payloads emitted by the Kotlin implementation through the matching
 * vanilla packet STREAM_CODEC. A passing result proves complete consumption
 * and successful official re-encoding without persisting a success report.
 */
public final class OfficialCodecOracle {
    private static final int MAX_REPORTED_FAILURES = 20;
    private static final Gson GSON = new Gson();

    private OfficialCodecOracle() {
    }

    public static void run(String fixturesJson) throws Exception {
        List<Fixture> fixtures = readFixtures(fixturesJson);
        Set<String> fixtureNames = new HashSet<>();
        for (Fixture fixture : fixtures) {
            String identity = "%s/%s".formatted(fixture.key().text(), fixture.sample());
            if (!fixtureNames.add(identity)) {
                throw new IllegalArgumentException(
                        "Duplicate fixture sample %s".formatted(identity)
                );
            }
        }

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess registries = createVanillaRegistryAccess();

        List<String> failures = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            try {
                byte[] encoded = passThroughOfficialCodec(
                        fixture.key(),
                        fixture.payload(),
                        registries
                );
                if (!Arrays.equals(fixture.payload(), encoded)) {
                    passThroughOfficialCodec(
                            fixture.key(),
                            encoded,
                            registries
                    );
                }
            } catch (Throwable error) {
                failures.add(
                        "%s/%s (%s): %s".formatted(
                                fixture.key().text(),
                                fixture.sample(),
                                fixture.kotlinClass(),
                                conciseError(error)
                        )
                );
            }
        }
        if (!failures.isEmpty()) {
            String details = failures.stream()
                    .limit(MAX_REPORTED_FAILURES)
                    .map(failure -> "- %s".formatted(failure))
                    .collect(Collectors.joining("\n"));
            String omitted =
                    failures.size() > MAX_REPORTED_FAILURES
                            ? "\n- ... %d additional failure(s) omitted".formatted(
                                    failures.size() - MAX_REPORTED_FAILURES
                            )
                            : "";
            throw new AssertionError(
                    "Official codec rejected %d fixture(s):\n%s%s".formatted(
                            failures.size(),
                            details,
                            omitted
                    )
            );
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static byte[] passThroughOfficialCodec(
            PacketKey key,
            byte[] payload,
            RegistryAccess registries
    ) {
        ProtocolInfo protocol = protocolFor(key, registries);
        StreamCodec<ByteBuf, Packet<?>> codec = protocol.codec();
        ByteBuf input = Unpooled.buffer();
        new FriendlyByteBuf(input).writeVarInt(parseId(key.id()));
        input.writeBytes(payload);
        Object packet = codec.decode(input);
        if (input.isReadable()) {
            throw new IllegalStateException(
                    "Vanilla left %d unread payload bytes".formatted(input.readableBytes())
            );
        }

        ByteBuf outputStorage = Unpooled.buffer();
        codec.encode(outputStorage, (Packet<?>) packet);
        FriendlyByteBuf output = new FriendlyByteBuf(outputStorage);
        int encodedId = output.readVarInt();
        if (encodedId != parseId(key.id())) {
            throw new IllegalStateException(
                    "Vanilla re-encoded packet ID %d, expected %d".formatted(
                            encodedId,
                            parseId(key.id())
                    )
            );
        }
        byte[] encoded = new byte[output.readableBytes()];
        output.readBytes(encoded);
        return encoded;
    }

    private static ProtocolInfo<?> protocolFor(
            PacketKey key,
            RegistryAccess registries
    ) {
        return switch (key.state()) {
            case "HANDSHAKE" -> {
                if (!key.direction().equals("SERVERBOUND")) {
                    throw new IllegalArgumentException(
                            "Handshake has no clientbound protocol"
                    );
                }
                yield HandshakeProtocols.SERVERBOUND;
            }
            case "STATUS" -> key.direction().equals("CLIENTBOUND")
                    ? StatusProtocols.CLIENTBOUND
                    : StatusProtocols.SERVERBOUND;
            case "LOGIN" -> key.direction().equals("CLIENTBOUND")
                    ? LoginProtocols.CLIENTBOUND
                    : LoginProtocols.SERVERBOUND;
            case "CONFIGURATION" -> key.direction().equals("CLIENTBOUND")
                    ? ConfigurationProtocols.CLIENTBOUND
                    : ConfigurationProtocols.SERVERBOUND;
            case "PLAY" -> key.direction().equals("CLIENTBOUND")
                    ? GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(registries)
            )
                    : GameProtocols.SERVERBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(registries),
                    () -> true
            );
            default -> throw new IllegalArgumentException(
                    "Unknown protocol state %s".formatted(key.state())
            );
        };
    }

    private static RegistryAccess createVanillaRegistryAccess() {
        RegistryAccess.Frozen staticRegistries =
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        HolderLookup.Provider vanillaLookups = VanillaRegistries.createLookup();
        List<Registry<?>> registries = staticRegistries.registries()
                .map(RegistryAccess.RegistryEntry::value)
                .collect(Collectors.toCollection(ArrayList::new));
        Set<Object> staticKeys = staticRegistries.registries()
                .map(RegistryAccess.RegistryEntry::key)
                .collect(Collectors.toCollection(HashSet::new));
        vanillaLookups.listRegistryKeys()
                .filter(key -> !staticKeys.contains(key))
                .forEach(key -> registries.add(materializeRegistry(vanillaLookups, key)));

        RegistryAccess result = new RegistryAccess.ImmutableRegistryAccess(registries);
        result.registries().forEach(
                entry -> entry.value().listElements().forEach(
                        holder -> holder.bindComponents(DataComponentMap.EMPTY)
                )
        );
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Registry<?> materializeRegistry(
            HolderLookup.Provider provider,
            ResourceKey key
    ) {
        HolderLookup.RegistryLookup lookup = provider.lookupOrThrow(key);
        MappedRegistry registry = new MappedRegistry(
                key,
                lookup.registryLifecycle()
        );
        lookup.listElements().forEach(
                rawHolder -> {
                    Holder.Reference holder = (Holder.Reference) rawHolder;
                    registry.register(
                            holder.key(),
                            holder.value(),
                            RegistrationInfo.BUILT_IN
                    );
                }
        );
        return registry.freeze();
    }

    private static List<Fixture> readFixtures(String fixturesJson) {
        FixtureInput[] inputs = GSON.fromJson(
                fixturesJson,
                FixtureInput[].class
        );
        if (inputs == null || inputs.length == 0) {
            throw new IllegalArgumentException("Codec fixtures are empty");
        }
        List<Fixture> fixtures = new ArrayList<>(inputs.length);
        for (FixtureInput input : inputs) {
            fixtures.add(
                    new Fixture(
                            PacketKey.of(
                                    input.state(),
                                    input.direction(),
                                    input.id()
                            ),
                            input.kotlinClass(),
                            input.sample(),
                            HexFormat.of().parseHex(input.payloadHex())
                    )
            );
        }
        return fixtures;
    }

    private static String normalizeId(String value) {
        int parsed = parseId(value);
        return "0x%s".formatted(Integer.toHexString(parsed).toUpperCase());
    }

    private static int parseId(String value) {
        return value.startsWith("0x") || value.startsWith("0X")
                ? Integer.parseInt(value.substring(2), 16)
                : Integer.parseInt(value);
    }

    private static String conciseError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        String suffix = message == null || message.isBlank()
                ? ""
                : ": %s".formatted(message);
        return "%s%s".formatted(current.getClass().getName(), suffix);
    }

    private record PacketKey(String state, String direction, String id) {
        static PacketKey of(String state, String direction, int id) {
            return new PacketKey(
                    state.toUpperCase(),
                    direction.toUpperCase(),
                    normalizeId(Integer.toString(id))
            );
        }

        String text() {
            return "%s/%s/%s".formatted(state, direction, id);
        }
    }

    private record FixtureInput(
            String state,
            String direction,
            int id,
            String kotlinClass,
            String sample,
            String payloadHex
    ) {
    }

    private record Fixture(
            PacketKey key,
            String kotlinClass,
            String sample,
            byte[] payload
    ) {
    }
}
