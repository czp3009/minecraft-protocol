import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.server.Bootstrap;

/**
 * Executes payloads emitted by the Kotlin implementation through the matching
 * vanilla packet STREAM_CODEC. A passing result proves complete consumption
 * and classifies exact, stable-normalizing, and non-deterministically
 * re-encoding official codecs from their observed behavior.
 */
public final class OfficialCodecOracle {
    private OfficialCodecOracle() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) {
            throw new IllegalArgumentException(
                    "usage: <fixtures.tsv> <official-packets.csv> <server-inner.jar> <report.json>"
            );
        }

        Path fixturesPath = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path packetIndexPath = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path serverJarPath = Path.of(arguments[2]).toAbsolutePath().normalize();
        Path reportPath = Path.of(arguments[3]).toAbsolutePath().normalize();

        Map<PacketKey, OfficialPacket> officialPackets =
                readOfficialPackets(packetIndexPath);
        List<Fixture> fixtures = readFixtures(fixturesPath);
        Set<PacketKey> fixtureKeys = fixtures.stream()
                .map(Fixture::key)
                .collect(Collectors.toSet());
        Set<String> fixtureNames = new HashSet<>();
        for (Fixture fixture : fixtures) {
            String identity = fixture.key().text() + "/" + fixture.sample();
            if (!fixtureNames.add(identity)) {
                throw new IllegalArgumentException(
                        "Duplicate fixture sample " + identity
                );
            }
        }

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess registries = createVanillaRegistryAccess();

        List<Map<String, Object>> results = new ArrayList<>(fixtures.size());
        int passed = 0;
        for (Fixture fixture : fixtures) {
            OfficialPacket official = officialPackets.get(fixture.key());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("key", fixture.key().text());
            result.put("sample", fixture.sample());
            result.put("kotlin_class", fixture.kotlinClass());
            result.put("payload_sha256", sha256(fixture.payload()));
            result.put("payload_size", fixture.payload().length);
            if (official == null) {
                result.put("status", "fail");
                result.put("error", "No matching official packet");
                results.add(result);
                continue;
            }
            result.put("official_name", official.name());
            result.put("official_class", official.className());
            result.put("official_source", official.sourcePath());
            result.put("official_source_sha256", official.sourceSha256());

            try {
                byte[] encoded = passThroughOfficialCodec(
                        fixture.key(),
                        fixture.payload(),
                        registries
                );
                boolean exact = Arrays.equals(fixture.payload(), encoded);
                String validation = "decode-and-byte-identical-reencode";
                if (!exact) {
                    byte[] normalized = passThroughOfficialCodec(
                            fixture.key(),
                            encoded,
                            registries
                    );
                    if (Arrays.equals(encoded, normalized)) {
                        result.put(
                                "normalized_payload_sha256",
                                sha256(encoded)
                        );
                        result.put("normalized_payload_size", encoded.length);
                        validation =
                                "complete-decode-and-stable-official-normalization";
                    } else {
                        validation =
                                "complete-decode-with-nondeterministic-official-reencoding";
                    }
                }
                result.put("validation", validation);
                result.put("status", "pass");
                passed++;
            } catch (Throwable error) {
                result.put("status", "fail");
                result.put("error", conciseError(error));
            }
            results.add(result);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1);
        report.put("generated_at", Instant.now().toString());
        report.put("minecraft_version", SharedConstants.getCurrentVersion().name());
        report.put("protocol_version", SharedConstants.getProtocolVersion());
        report.put("official_server_inner_sha256", sha256(Files.readAllBytes(serverJarPath)));
        report.put("fixture_sha256", sha256(Files.readAllBytes(fixturesPath)));
        report.put("expected_packet_count", officialPackets.size());
        report.put("covered_packet_count", fixtureKeys.size());
        report.put("fixture_count", fixtures.size());
        report.put("passed", passed);
        report.put("failed", fixtures.size() - passed);
        report.put("results", results);

        Files.createDirectories(reportPath.getParent());
        Files.writeString(
                reportPath,
                new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n"
        );
        System.err.printf(
                "Official codec oracle: %d/%d passed; report: %s%n",
                passed,
                fixtures.size(),
                reportPath
        );
        if (!officialPackets.keySet().equals(fixtureKeys)
                || passed != fixtures.size()) {
            System.exit(1);
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
                    "Vanilla left " + input.readableBytes() + " unread payload bytes"
            );
        }

        ByteBuf outputStorage = Unpooled.buffer();
        codec.encode(outputStorage, (Packet<?>) packet);
        FriendlyByteBuf output = new FriendlyByteBuf(outputStorage);
        int encodedId = output.readVarInt();
        if (encodedId != parseId(key.id())) {
            throw new IllegalStateException(
                    "Vanilla re-encoded packet ID " + encodedId
                            + ", expected " + parseId(key.id())
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
                    "Unknown protocol state " + key.state()
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
            net.minecraft.resources.ResourceKey key
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

    private static Map<PacketKey, OfficialPacket> readOfficialPackets(Path path)
            throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Official packet index is empty: " + path);
        }
        String[] header = lines.getFirst().split(",", -1);
        Map<String, Integer> columns = columns(header);
        Map<PacketKey, OfficialPacket> packets = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split(",", -1);
            PacketKey key = PacketKey.of(
                    field(fields, columns, "state"),
                    field(fields, columns, "direction"),
                    field(fields, columns, "id")
            );
            OfficialPacket previous = packets.put(
                    key,
                    new OfficialPacket(
                            field(fields, columns, "official_name"),
                            field(fields, columns, "official_class"),
                            field(fields, columns, "source_path"),
                            field(fields, columns, "source_sha256")
                    )
            );
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate official packet key " + key.text()
                );
            }
        }
        return packets;
    }

    private static List<Fixture> readFixtures(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("Fixture file is empty: " + path);
        }
        String[] header = lines.getFirst().split("\t", -1);
        Map<String, Integer> columns = columns(header);
        List<Fixture> fixtures = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            fixtures.add(
                    new Fixture(
                            PacketKey.of(
                                    field(fields, columns, "state"),
                                    field(fields, columns, "direction"),
                                    field(fields, columns, "id")
                            ),
                            field(fields, columns, "kotlin_class"),
                            field(fields, columns, "sample"),
                            HexFormat.of().parseHex(field(fields, columns, "payload_hex"))
                    )
            );
        }
        return fixtures;
    }

    private static Map<String, Integer> columns(String[] header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int index = 0; index < header.length; index++) {
            columns.put(header[index], index);
        }
        return columns;
    }

    private static String field(
            String[] fields,
            Map<String, Integer> columns,
            String name
    ) {
        Integer index = columns.get(name);
        if (index == null || index >= fields.length) {
            throw new IllegalArgumentException("Missing column " + name);
        }
        return fields[index];
    }

    private static String normalizeId(String value) {
        int parsed = parseId(value);
        return "0x" + Integer.toHexString(parsed).toUpperCase();
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
        return current.getClass().getName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content)
        );
    }

    private record PacketKey(String state, String direction, String id) {
        static PacketKey of(String state, String direction, String id) {
            return new PacketKey(
                    state.toUpperCase(),
                    direction.toUpperCase(),
                    normalizeId(id)
            );
        }

        String text() {
            return state + "/" + direction + "/" + id;
        }
    }

    private record OfficialPacket(
            String name,
            String className,
            String sourcePath,
            String sourceSha256
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
