package com.xetax.crm.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads {@code .env} into the Spring Environment so the {@code ${SMTP_HOST:}}
 * style placeholders in application.yaml resolve no matter how the app was
 * launched — IntelliJ run config, plain {@code mvn spring-boot:run}, or the
 * restart script. Real OS environment variables and -D system properties
 * still win: this source is added LAST, so it only fills in what is missing.
 *
 * <p>Looked up in the working directory and its parents (IntelliJ often runs
 * with the repo root as cwd). Lines are {@code KEY=VALUE}; blank lines and
 * {@code #} comments are skipped; surrounding single/double quotes and an
 * optional {@code export } prefix are stripped.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "dotenv";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path file = locate();
        if (file == null) return;
        Map<String, Object> values = parse(file);
        if (values.isEmpty()) return;
        environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, values));
        System.out.println("[dotenv] loaded " + values.size() + " keys from " + file.toAbsolutePath());
    }

    private static Path locate() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve(".env");
            if (Files.isRegularFile(candidate)) return candidate;
            // The backend module keeps its .env in crm/ even when cwd is the repo root.
            Path nested = dir.resolve("crm").resolve(".env");
            if (Files.isRegularFile(nested)) return nested;
            dir = dir.getParent();
        }
        return null;
    }

    static Map<String, Object> parse(Path file) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).strip();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                }
                out.put(key, value);
            }
        } catch (IOException e) {
            System.err.println("[dotenv] could not read " + file + ": " + e.getMessage());
        }
        return out;
    }
}
