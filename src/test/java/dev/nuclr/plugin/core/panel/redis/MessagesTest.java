/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MessagesTest {

	/** Every translation shipped with the plugin. Add a language here when adding its file. */
	private static final List<String> TRANSLATIONS = List.of();

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[^}]*}");
	private static final Pattern TR_CALL = Pattern.compile("\\btr\\(\\s*\"([^\"]+)\"\\s*[,)]");

	@AfterEach
	void backToEnglish() {
		Messages.use(Locale.ENGLISH);
	}

	private static Properties bundle(String suffix) throws IOException {
		String name = "/" + Messages.BUNDLE.replace('.', '/') + suffix + ".properties";
		try (InputStream in = MessagesTest.class.getResourceAsStream(name)) {
			assertTrue(in != null, "missing " + name);
			Properties properties = new Properties();
			properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
			return properties;
		}
	}

	private static Set<String> placeholders(String text) {
		Set<String> found = new TreeSet<>();
		Matcher matcher = PLACEHOLDER.matcher(text);
		while (matcher.find()) {
			found.add(matcher.group(1));
		}
		return found;
	}

	@Test
	void everyTranslationHasExactlyTheEnglishKeysAndPlaceholders() throws IOException {
		Properties english = bundle("");
		for (String language : TRANSLATIONS) {
			Properties translation = bundle("_" + language);
			assertEquals(new TreeSet<>(english.stringPropertyNames()), new TreeSet<>(translation.stringPropertyNames()),
					language + " keys differ from English");
			for (String key : english.stringPropertyNames()) {
				String text = translation.getProperty(key);
				assertFalse(text.isBlank(), language + " " + key + " is blank");
				assertEquals(placeholders(english.getProperty(key)), placeholders(text),
						language + " " + key + " placeholders differ");
			}
		}
	}

	@Test
	void everyKeyUsedInTheCodeExistsInEnglish() throws IOException {
		Properties english = bundle("");
		List<String> missing = new ArrayList<>();
		try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
			for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
				Matcher matcher = TR_CALL.matcher(Files.readString(source));
				while (matcher.find()) {
					if (!english.containsKey(matcher.group(1))) {
						missing.add(source.getFileName() + ": " + matcher.group(1));
					}
				}
			}
		}
		// Keys built or chosen at the call rather than written inside tr(...).
		for (String key : List.of("edit.shape.hash", "edit.shape.list", "edit.shape.set", "edit.shape.zset",
				"delete.confirm.folder.partial", "delete.confirm.mixed.partial", "secret.prompt.refused")) {
			if (!english.containsKey(key)) {
				missing.add("ValueCodec: " + key);
			}
		}
		assertEquals(List.of(), missing);
	}

	@Test
	void noLongDashesInUserFacingTexts() throws IOException {
		Properties english = bundle("");
		for (String key : english.stringPropertyNames()) {
			String text = english.getProperty(key);
			assertFalse(text.contains("—") || text.contains("–"), key + " uses a long dash: " + text);
		}
	}

	@Test
	void fallsBackToEnglishAndToTheKey() {
		Messages.use(Locale.GERMAN);
		assertEquals("Cancel", Messages.tr("common.cancel"));
		assertEquals("no.such.key", Messages.tr("no.such.key"));
	}
}
