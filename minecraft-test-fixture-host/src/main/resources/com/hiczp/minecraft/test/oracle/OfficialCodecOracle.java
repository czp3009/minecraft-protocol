package com.hiczp.minecraft.test.oracle;

import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
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
            String identity = "%s/%s".formatted(fixture.packetKey().text(), fixture.sample());
            if (!fixtureNames.add(identity)) {
                throw new IllegalArgumentException(
                        "Duplicate fixture sample %s".formatted(identity)
                );
            }
        }

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess registryAccess = createVanillaRegistryAccess();

        List<String> failures = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            try {
                byte[] encoded = passThroughOfficialCodec(
                        fixture.packetKey(),
                        fixture.payload(),
                        registryAccess
                );
                if (!Arrays.equals(fixture.payload(), encoded)) {
                    passThroughOfficialCodec(
                            fixture.packetKey(),
                            encoded,
                            registryAccess
                    );
                }
            } catch (Throwable error) {
                failures.add(
                        "%s/%s (%s): %s".formatted(
                                fixture.packetKey().text(),
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

    /** Cross-reads NBT bytes with the matching vanilla NbtIo root method. */
    public static void runNbt(String fixturesJson) throws Exception {
        List<NbtFixture> fixtures = readNbtFixtures(fixturesJson);
        Set<String> fixtureNames = new HashSet<>();
        for (NbtFixture nbtFixture : fixtures) {
            String identity = "%s/%s".formatted(nbtFixture.nbtRootMode(), nbtFixture.sample());
            if (!fixtureNames.add(identity)) {
                throw new IllegalArgumentException(
                        "Duplicate NBT fixture sample %s".formatted(identity)
                );
            }
        }

        List<String> failures = new ArrayList<>();
        for (NbtFixture nbtFixture : fixtures) {
            try {
                byte[] encoded = passThroughOfficialNbt(
                        nbtFixture.nbtRootMode(),
                        nbtFixture.payload()
                );
                if (nbtFixture.reject()) {
                    failures.add(
                            "%s/%s: vanilla accepted a rejected fixture".formatted(
                                    nbtFixture.nbtRootMode(),
                                    nbtFixture.sample()
                            )
                    );
                    continue;
                }
                byte[] expected = nbtFixture.exactBytes()
                        ? nbtFixture.expected()
                        : passThroughOfficialNbt(
                                nbtFixture.nbtRootMode(),
                                nbtFixture.expected()
                        );
                if (!Arrays.equals(expected, encoded)) {
                    throw new AssertionError(
                            "Vanilla encoded %s, expected %s".formatted(
                                    HexFormat.of().formatHex(encoded),
                                    HexFormat.of().formatHex(expected)
                            )
                    );
                }
            } catch (Throwable error) {
                if (!nbtFixture.reject() || !(error instanceof Exception)) {
                    failures.add(
                            "%s/%s: %s".formatted(
                                    nbtFixture.nbtRootMode(),
                                    nbtFixture.sample(),
                                    conciseError(error)
                            )
                    );
                }
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
                    "Official NBT codec rejected %d fixture(s):\n%s%s".formatted(
                            failures.size(),
                            details,
                            omitted
                    )
            );
        }
    }

    /** Parses SNBT with the matching vanilla grammar and compares its NBT value. */
    public static void runSnbt(String fixturesJson) throws Exception {
        List<SnbtFixture> fixtures = readSnbtFixtures(fixturesJson);
        Set<String> fixtureNames = new HashSet<>();
        for (SnbtFixture snbtFixture : fixtures) {
            if (!fixtureNames.add(snbtFixture.sample())) {
                throw new IllegalArgumentException(
                        "Duplicate SNBT fixture sample %s".formatted(snbtFixture.sample())
                );
            }
        }

        List<String> failures = new ArrayList<>();
        for (SnbtFixture snbtFixture : fixtures) {
            try {
                Tag parsed = TagParser.create(NbtOps.INSTANCE).parseFully(snbtFixture.input());
                if (snbtFixture.reject()) {
                    failures.add(
                            "%s: vanilla accepted a rejected fixture".formatted(snbtFixture.sample())
                    );
                    continue;
                }
                Tag expected = readAnyNbt(snbtFixture.expected());
                if (!expected.equals(parsed)) {
                    throw new AssertionError(
                            "Vanilla parsed %s, expected %s".formatted(
                                    parsed,
                                    expected
                            )
                    );
                }

                Tag reparsed = TagParser.create(NbtOps.INSTANCE).parseFully(parsed.toString());
                if (!parsed.equals(reparsed)) {
                    throw new AssertionError("Vanilla SNBT writer did not round-trip its parsed value");
                }
            } catch (Throwable error) {
                if (!snbtFixture.reject() || !(error instanceof Exception)) {
                    failures.add(
                            "%s: %s".formatted(
                                    snbtFixture.sample(),
                                    conciseError(error)
                            )
                    );
                }
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
                    "Official SNBT parser rejected %d fixture(s):\n%s%s".formatted(
                            failures.size(),
                            details,
                            omitted
                    )
            );
        }
    }

    private static Tag readAnyNbt(byte[] bytes) throws Exception {
        ByteArrayInputStream storage = new ByteArrayInputStream(bytes);
        DataInputStream input = new DataInputStream(storage);
        Tag nbtTag = NbtIo.readAnyTag(input, NbtAccounter.unlimitedHeap());
        if (storage.available() != 0) {
            throw new IllegalStateException(
                    "Expected NBT has %d trailing byte(s)".formatted(storage.available())
            );
        }
        return nbtTag;
    }

    private static byte[] passThroughOfficialNbt(
            NbtRootMode nbtRootMode,
            byte[] payload
    ) throws Exception {
        ByteArrayInputStream storage = new ByteArrayInputStream(payload);
        DataInputStream input = new DataInputStream(storage);
        ByteArrayOutputStream outputStorage = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(outputStorage);
        switch (nbtRootMode) {
            case ANY -> {
                Tag tag = NbtIo.readAnyTag(input, NbtAccounter.unlimitedHeap());
                NbtIo.writeAnyTag(tag, output);
            }
            case UNNAMED -> {
                Tag tag = NbtIo.readUnnamedTag(input, NbtAccounter.unlimitedHeap());
                NbtIo.writeUnnamedTag(tag, output);
            }
            case DOCUMENT -> {
                CompoundTag tag = NbtIo.read(input, NbtAccounter.unlimitedHeap());
                NbtIo.write(tag, output);
            }
        }
        if (storage.available() != 0) {
            throw new IllegalStateException(
                    "Vanilla left %d unread NBT bytes".formatted(storage.available())
            );
        }
        output.flush();
        return outputStorage.toByteArray();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static byte[] passThroughOfficialCodec(
            PacketKey packetKey,
            byte[] payload,
            RegistryAccess registryAccess
    ) {
        ProtocolInfo protocolInfo = protocolFor(packetKey, registryAccess);
        StreamCodec<ByteBuf, Packet<?>> streamCodec = protocolInfo.codec();
        ByteBuf input = Unpooled.buffer();
        new FriendlyByteBuf(input).writeVarInt(parseId(packetKey.id()));
        input.writeBytes(payload);
        Object packet = streamCodec.decode(input);
        if (input.isReadable()) {
            throw new IllegalStateException(
                    "Vanilla left %d unread payload bytes".formatted(input.readableBytes())
            );
        }

        ByteBuf outputStorage = Unpooled.buffer();
        streamCodec.encode(outputStorage, (Packet<?>) packet);
        FriendlyByteBuf output = new FriendlyByteBuf(outputStorage);
        int encodedId = output.readVarInt();
        if (encodedId != parseId(packetKey.id())) {
            throw new IllegalStateException(
                    "Vanilla re-encoded packet ID %d, expected %d".formatted(
                            encodedId,
                            parseId(packetKey.id())
                    )
            );
        }
        byte[] encoded = new byte[output.readableBytes()];
        output.readBytes(encoded);
        return encoded;
    }

    private static ProtocolInfo<?> protocolFor(
            PacketKey packetKey,
            RegistryAccess registryAccess
    ) {
        return switch (packetKey.state()) {
            case "HANDSHAKE" -> {
                if (!packetKey.direction().equals("SERVERBOUND")) {
                    throw new IllegalArgumentException(
                            "Handshake has no clientbound protocol"
                    );
                }
                yield HandshakeProtocols.SERVERBOUND;
            }
            case "STATUS" -> packetKey.direction().equals("CLIENTBOUND")
                    ? StatusProtocols.CLIENTBOUND
                    : StatusProtocols.SERVERBOUND;
            case "LOGIN" -> packetKey.direction().equals("CLIENTBOUND")
                    ? LoginProtocols.CLIENTBOUND
                    : LoginProtocols.SERVERBOUND;
            case "CONFIGURATION" -> packetKey.direction().equals("CLIENTBOUND")
                    ? ConfigurationProtocols.CLIENTBOUND
                    : ConfigurationProtocols.SERVERBOUND;
            case "PLAY" -> packetKey.direction().equals("CLIENTBOUND")
                    ? GameProtocols.CLIENTBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(registryAccess)
            )
                    : GameProtocols.SERVERBOUND_TEMPLATE.bind(
                    RegistryFriendlyByteBuf.decorator(registryAccess),
                    () -> true
            );
            default -> throw new IllegalArgumentException(
                    "Unknown protocol state %s".formatted(packetKey.state())
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
                .filter(resourceKey -> !staticKeys.contains(resourceKey))
                .forEach(resourceKey -> registries.add(materializeRegistry(vanillaLookups, resourceKey)));

        RegistryAccess registryAccess = new RegistryAccess.ImmutableRegistryAccess(registries);
        registryAccess.registries().forEach(
                entry -> entry.value().listElements().forEach(
                        holderReference -> holderReference.bindComponents(DataComponentMap.EMPTY)
                )
        );
        return registryAccess;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Registry<?> materializeRegistry(
            HolderLookup.Provider holderLookupProvider,
            ResourceKey resourceKey
    ) {
        HolderLookup.RegistryLookup registryLookup = holderLookupProvider.lookupOrThrow(resourceKey);
        MappedRegistry mappedRegistry = new MappedRegistry(
                resourceKey,
                registryLookup.registryLifecycle()
        );
        registryLookup.listElements().forEach(
                rawHolder -> {
                    Holder.Reference holderReference = (Holder.Reference) rawHolder;
                    mappedRegistry.register(
                            holderReference.key(),
                            holderReference.value(),
                            RegistrationInfo.BUILT_IN
                    );
                }
        );
        return mappedRegistry.freeze();
    }

    private static List<Fixture> readFixtures(String fixturesJson) {
        FixtureInput[] fixtureInputs = GSON.fromJson(
                fixturesJson,
                FixtureInput[].class
        );
        if (fixtureInputs == null || fixtureInputs.length == 0) {
            throw new IllegalArgumentException("Codec fixtures are empty");
        }
        List<Fixture> fixtures = new ArrayList<>(fixtureInputs.length);
        for (FixtureInput fixtureInput : fixtureInputs) {
            fixtures.add(
                    new Fixture(
                            PacketKey.of(
                                    fixtureInput.state(),
                                    fixtureInput.direction(),
                                    fixtureInput.id()
                            ),
                            fixtureInput.kotlinClass(),
                            fixtureInput.sample(),
                            HexFormat.of().parseHex(fixtureInput.payloadHex())
                    )
            );
        }
        return fixtures;
    }

    private static List<NbtFixture> readNbtFixtures(String fixturesJson) {
        NbtFixtureInput[] nbtFixtureInputs = GSON.fromJson(
                fixturesJson,
                NbtFixtureInput[].class
        );
        if (nbtFixtureInputs == null || nbtFixtureInputs.length == 0) {
            throw new IllegalArgumentException("NBT fixtures are empty");
        }
        List<NbtFixture> fixtures = new ArrayList<>(nbtFixtureInputs.length);
        for (NbtFixtureInput nbtFixtureInput : nbtFixtureInputs) {
            fixtures.add(
                    new NbtFixture(
                            NbtRootMode.valueOf(nbtFixtureInput.mode().toUpperCase()),
                            nbtFixtureInput.sample(),
                            HexFormat.of().parseHex(nbtFixtureInput.payloadHex()),
                            HexFormat.of().parseHex(nbtFixtureInput.expectedHex()),
                            nbtFixtureInput.exactBytes(),
                            nbtFixtureInput.reject()
                    )
            );
        }
        return fixtures;
    }

    private static List<SnbtFixture> readSnbtFixtures(String fixturesJson) {
        SnbtFixtureInput[] snbtFixtureInputs = GSON.fromJson(
                fixturesJson,
                SnbtFixtureInput[].class
        );
        if (snbtFixtureInputs == null || snbtFixtureInputs.length == 0) {
            throw new IllegalArgumentException("SNBT fixtures are empty");
        }
        List<SnbtFixture> fixtures = new ArrayList<>(snbtFixtureInputs.length);
        for (SnbtFixtureInput snbtFixtureInput : snbtFixtureInputs) {
            fixtures.add(
                    new SnbtFixture(
                            snbtFixtureInput.sample(),
                            snbtFixtureInput.input(),
                            HexFormat.of().parseHex(snbtFixtureInput.expectedHex()),
                            snbtFixtureInput.reject()
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
            PacketKey packetKey,
            String kotlinClass,
            String sample,
            byte[] payload
    ) {
    }

    private enum NbtRootMode {
        ANY,
        UNNAMED,
        DOCUMENT
    }

    private record NbtFixtureInput(
            String mode,
            String sample,
            String payloadHex,
            String expectedHex,
            boolean exactBytes,
            boolean reject
    ) {
    }

    private record NbtFixture(
            NbtRootMode nbtRootMode,
            String sample,
            byte[] payload,
            byte[] expected,
            boolean exactBytes,
            boolean reject
    ) {
    }

    private record SnbtFixtureInput(
            String sample,
            String input,
            String expectedHex,
            boolean reject
    ) {
    }

    private record SnbtFixture(
            String sample,
            String input,
            byte[] expected,
            boolean reject
    ) {
    }
}
