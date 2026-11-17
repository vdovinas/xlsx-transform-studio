package local.xlsx.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import local.xlsx.model.Profile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ProfileRepository {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ProfileRepository() {
    }

    public static <T> T copy(T value, Class<T> type) {
        return JSON.convertValue(value, type);
    }

    public static Profile copy(Profile p) {
        return JSON.convertValue(p, Profile.class);
    }

    public static Profile load(Path path) throws IOException {
        Profile p = JSON.readValue(path.toFile(), Profile.class);
        return p;
    }

    public static void save(Path path, Profile profile) throws IOException {
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), "profile-", ".json");
        try {
            JSON.writeValue(temp.toFile(), profile);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static List<Profile> builtins() {
        try (InputStream in = ProfileRepository.class.getResourceAsStream("/profiles.json")) {
            return new ArrayList<>(Arrays.asList(JSON.readValue(in, Profile[].class)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
