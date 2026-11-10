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
package dev.nuclr.plugin.core.panel.redis.edit;

import static dev.nuclr.plugin.core.panel.redis.Messages.tr;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.redis.client.RedisSession.WriteResult;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue;
import dev.nuclr.plugin.core.panel.redis.resource.RedisResource;
import dev.nuclr.plugin.core.panel.redis.value.ValueCodec;
import lombok.extern.slf4j.Slf4j;

/**
 * F4 on a key: the value is written to a temporary file as text (see {@link ValueCodec}),
 * Commander's editor opens it, and every save goes back to Redis.
 *
 * <p>A save is written only while the server still holds the value the edit started
 * from; if someone else changed it meanwhile, the user is asked before it is overwritten.
 * The key's TTL is kept. Text that does not parse (a hash edited into broken JSON) is
 * reported and the edit stays open for another save. When the editor closes, a last save
 * is picked up and the temporary file removed.
 */
@Slf4j
public final class ValueEditService implements NuclrEventListener {

	/** Elements, or bytes of a string, an edit reads; a bigger value is not opened. */
	public static final int EDIT_ITEMS = 50_000;
	public static final int EDIT_BYTES = 8 * 1024 * 1024;

	private static final long POLL_MILLIS = 750;
	private static final long CLOSE_GRACE_MILLIS = 1_500;
	private static final String EVENT_FULLSCREEN_CLOSE = "plugin.fullscreen.close";
	private static final String EVENT_EDIT = "mainpanel.edit";
	private static final Pattern UNSAFE = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

	/** Writes a value back; the panel's connection behind it. */
	@FunctionalInterface
	public interface Writer {
		WriteResult replace(RedisResource key, RedisValue expected, RedisValue value, boolean force) throws Exception;
	}

	/** The dialogs an edit may need; a seam so tests answer without a display. */
	public interface Prompts {
		boolean confirmOverwrite(String keyName);

		void error(String title, String message);
	}

	/** One open edit. Package-private so tests can drive {@link #syncOnce}. */
	static final class Session {
		final RedisResource key;
		final Path tempDir;
		final Path tempFile;
		RedisValue expected;
		long lastSize;
		long lastModified;
		volatile boolean closing;
		volatile boolean stopped;
		/**
		 * The file holds text that is not on the server: a save that was refused, failed or
		 * did not parse. Such a file outlives the session, since the user was told it is kept.
		 */
		volatile boolean unsaved;

		Session(RedisResource key, Path tempDir, Path tempFile, RedisValue expected) {
			this.key = key;
			this.tempDir = tempDir;
			this.tempFile = tempFile;
			this.expected = expected;
		}
	}

	private final Writer writer;
	private final Prompts prompts;
	private final Map<Path, Session> sessions = new ConcurrentHashMap<>();

	public ValueEditService(Writer writer, Prompts prompts) {
		this.writer = writer;
		this.prompts = prompts;
	}

	/**
	 * Reads the value, writes it to a temporary file and asks Commander to open it in an
	 * editor. Blocks on the network, so call it off the event dispatch thread.
	 *
	 * @return the temporary file, or {@code null} when the value cannot be edited (the
	 *         user has been told why)
	 */
	public Path edit(RedisResource key, NuclrEventBus bus) {
		Session session;
		try {
			session = prepare(key);
		} catch (ValueCodec.NotEditable e) {
			prompts.error(tr("edit.title"), e.getMessage());
			return null;
		} catch (Exception e) {
			log.warn("Could not open {} for editing: {}", key.getFullPath(), e.getMessage(), e);
			prompts.error(tr("edit.title"), tr("edit.read.failed", key.getName(), String.valueOf(e.getMessage())));
			return null;
		}
		sessions.put(session.tempFile, session);
		if (bus != null) {
			bus.emit(EVENT_EDIT, Map.of("resource", new LocalEditResource(session.tempFile, key)), null);
		}
		Thread.ofVirtual().name("redis-edit-sync").start(() -> watch(session));
		return session.tempFile;
	}

	/** The temporary copy of {@code key}'s value, ready to edit. */
	Session prepare(RedisResource key) throws Exception {
		RedisValue value = key.readValue(EDIT_ITEMS, EDIT_BYTES);
		String text = ValueCodec.toEditText(value);
		Path dir = Files.createTempDirectory("nuclr-redis-edit-");
		Path file = dir.resolve(fileName(key.getName()) + ValueCodec.fileExtension(value.type(), text));
		Files.writeString(file, text, StandardCharsets.UTF_8);
		var session = new Session(key, dir, file, value);
		recordStamp(session);
		return session;
	}

	static String fileName(String keyName) {
		String safe = UNSAFE.matcher(keyName == null ? "" : keyName).replaceAll("_").strip();
		if (safe.length() > 80) {
			safe = safe.substring(0, 80);
		}
		return safe.isEmpty() || safe.endsWith(".") ? safe + "value" : safe;
	}

	private void watch(Session session) {
		long closingSince = 0;
		while (!session.stopped) {
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
			if (session.closing && closingSince == 0) {
				closingSince = System.currentTimeMillis();
			}
			syncOnce(session);
			if (closingSince > 0 && System.currentTimeMillis() - closingSince >= CLOSE_GRACE_MILLIS) {
				session.stopped = true;
			}
		}
		finish(session);
	}

	/** Ends a session: the temporary file goes, unless it holds the user's only copy of their edit. */
	void finish(Session session) {
		sessions.remove(session.tempFile);
		if (session.unsaved) {
			log.info("Keeping {}: it holds an edit that is not on the server", session.tempFile);
		} else {
			cleanup(session);
		}
	}

	/** One look at the temporary file: writes it back when it changed since the last look. */
	void syncOnce(Session session) {
		if (session.stopped || !localChanged(session)) {
			return;
		}
		try {
			recordStamp(session);
			String text = Files.readString(session.tempFile, StandardCharsets.UTF_8);
			RedisValue edited;
			try {
				edited = ValueCodec.fromEditText(session.expected.type(), text);
			} catch (IllegalArgumentException e) {
				// The file stays open in the editor: the next save tries again.
				session.unsaved = true;
				prompts.error(tr("edit.save.title"), tr("edit.parse.failed", session.key.getName(), e.getMessage()));
				return;
			}
			WriteResult result = writer.replace(session.key, session.expected, edited, false);
			if (result == WriteResult.CHANGED) {
				if (!prompts.confirmOverwrite(session.key.getName())) {
					keep(session);
					return;
				}
				// Unconditional by contract; checked anyway, since a "saved" that was not is the
				// one outcome the user must never be left believing.
				if (writer.replace(session.key, session.expected, edited, true) != WriteResult.WRITTEN) {
					keep(session);
					return;
				}
			}
			session.unsaved = false;
			// What the server holds now: a set drops duplicates, a sorted set reorders by score.
			session.expected = session.key.readValue(EDIT_ITEMS, EDIT_BYTES);
		} catch (Exception e) {
			log.warn("Saving {} to Redis failed: {}", session.key.getFullPath(), e.getMessage(), e);
			prompts.error(tr("edit.save.title"),
					tr("edit.save.failed", session.key.getName(), String.valueOf(e.getMessage()), session.tempFile.toString()));
			// Ends the session but keeps the file, so the edits are not lost.
			session.unsaved = true;
			session.stopped = true;
		}
	}

	/** Stops syncing and keeps the file, telling the user where their version is. */
	private void keep(Session session) {
		session.unsaved = true;
		session.stopped = true;
		prompts.error(tr("edit.save.title"), tr("edit.skipped", session.tempFile.toString()));
	}

	private static boolean localChanged(Session session) {
		try {
			BasicFileAttributes attrs = Files.readAttributes(session.tempFile, BasicFileAttributes.class);
			return attrs.size() != session.lastSize || attrs.lastModifiedTime().toMillis() != session.lastModified;
		} catch (IOException e) {
			return false;
		}
	}

	private static void recordStamp(Session session) throws IOException {
		BasicFileAttributes attrs = Files.readAttributes(session.tempFile, BasicFileAttributes.class);
		session.lastSize = attrs.size();
		session.lastModified = attrs.lastModifiedTime().toMillis();
	}

	private static void cleanup(Session session) {
		try {
			Files.deleteIfExists(session.tempFile);
			Files.deleteIfExists(session.tempDir);
		} catch (IOException e) {
			log.debug("Could not remove {}: {}", session.tempFile, e.getMessage());
		}
	}

	/** Stops every edit; the temporary files of unsaved ones stay for the user. */
	public void stopAll() {
		for (Session session : sessions.values()) {
			session.closing = true;
		}
	}

	int openSessions() {
		return sessions.size();
	}

	@Override
	public void handleMessage(Object source, String type, Map<String, Object> eventData, NuclrPluginCallback callback) {
		if (EVENT_FULLSCREEN_CLOSE.equals(type)) {
			// The editor's save-on-close may still be writing; the watcher takes one more look.
			for (Session session : sessions.values()) {
				session.closing = true;
			}
		}
	}

	@Override
	public boolean isMessageSupported(String type) {
		return EVENT_FULLSCREEN_CLOSE.equals(type);
	}

	/** The temporary file as Commander's editor sees it: a plain local file named after the key. */
	public static final class LocalEditResource extends NuclrResource {

		private static final long serialVersionUID = 1L;

		LocalEditResource(Path tempFile, RedisResource key) {
			super(tempFile);
			this.name = tempFile.getFileName().toString();
			setUuid("redis:edit:" + key.getUuid() + ":" + tempFile);
			setFullPath(tempFile.toString());
			setFolder(false);
			setReadable(true);
			try {
				setLength(Files.size(tempFile));
			} catch (IOException e) {
				setLength(0);
			}
		}

		@Override
		public InputStream openInputStream(OpenOption... options) throws Exception {
			return Files.newInputStream(getPath(), options);
		}
	}
}
