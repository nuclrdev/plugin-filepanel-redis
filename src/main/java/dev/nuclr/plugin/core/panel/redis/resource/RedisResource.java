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
package dev.nuclr.plugin.core.panel.redis.resource;

import static dev.nuclr.plugin.core.panel.redis.Messages.tr;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.OpenOption;
import java.util.Base64;
import java.util.Locale;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.redis.client.KeyInfo;
import dev.nuclr.plugin.core.panel.redis.client.KeyType;
import dev.nuclr.plugin.core.panel.redis.client.RedisValue;
import dev.nuclr.plugin.core.panel.redis.connection.RedisConnectionProfile;
import dev.nuclr.plugin.core.panel.redis.keys.KeyNames;
import dev.nuclr.plugin.core.panel.redis.keys.KeyTree;
import dev.nuclr.plugin.core.panel.redis.value.ValueCodec;

/**
 * One row of the Redis panel: {@code Explorer root -> Connection -> Database -> folders ->
 * key}. Folders and keys carry raw bytes, so a key that is not UTF-8 still names exactly
 * the key it came from.
 */
public final class RedisResource extends NuclrResource {

	private static final long serialVersionUID = 1L;

	private static final String SCHEME = "redis-explorer://";

	/** Elements, or bytes of a string, a viewer reading a key as a file gets. */
	public static final int DOCUMENT_ITEMS = 10_000;
	public static final int DOCUMENT_BYTES = 16 * 1024 * 1024;

	/** Reads the value of a key row; set by the panel, which owns the connection. */
	@FunctionalInterface
	public interface ValueReader {
		RedisValue read(RedisResource key, int itemCap, int byteCap) throws Exception;
	}

	private final ResourceKind kind;
	private String profileId;
	private int db = -1;
	/** A folder's prefix, separator included; a key's whole name. */
	private byte[] bytes;
	private KeyType keyType;
	private String typeName;
	private long ttlMillis = KeyInfo.NO_EXPIRY;
	private boolean readOnly;
	/** A folder whose key count comes from a listing cut short: the real count may be far higher. */
	private boolean countPartial;

	private transient ValueReader reader;

	private RedisResource(ResourceKind kind) {
		super(null);
		this.kind = kind;
	}

	public ResourceKind getKind() {
		return kind;
	}

	public String getProfileId() {
		return profileId;
	}

	public int getDb() {
		return db;
	}

	/** A folder's prefix (separator included), a key's name; empty at a database's top. */
	public byte[] getBytes() {
		return bytes == null ? new byte[0] : bytes;
	}

	public KeyType getKeyType() {
		return keyType;
	}

	public String getTypeName() {
		return typeName;
	}

	public long getTtlMillis() {
		return ttlMillis;
	}

	/** Whether the connection was saved read-only, so F4 and F8 stay off its keys. */
	public boolean isReadOnly() {
		return readOnly;
	}

	public boolean isCountPartial() {
		return countPartial;
	}

	public boolean isKey() {
		return kind == ResourceKind.KEY;
	}

	public RedisResource withReader(ValueReader valueReader) {
		this.reader = valueReader;
		return this;
	}

	public boolean canRead() {
		return reader != null && keyType != null && keyType.readable();
	}

	public RedisValue readValue(int itemCap, int byteCap) throws Exception {
		if (reader == null) {
			throw new IOException("This key is no longer connected to its server.");
		}
		return reader.read(this, itemCap, byteCap);
	}

	/** The value as a document: a string's bytes, anything else as JSON. */
	@Override
	public InputStream openInputStream(OpenOption... options) throws Exception {
		if (!isKey()) {
			return super.openInputStream(options);
		}
		return new ByteArrayInputStream(ValueCodec.document(readValue(DOCUMENT_ITEMS, DOCUMENT_BYTES)));
	}

	// ------------------------------------------------------------------
	// Factories
	// ------------------------------------------------------------------

	public static RedisResource explorerRoot() {
		var r = new RedisResource(ResourceKind.EXPLORER_ROOT);
		r.setUuid(SCHEME);
		r.setFullPath(SCHEME);
		r.setName("Redis Explorer");
		r.setFolder(true);
		return r;
	}

	public static RedisResource connection(RedisConnectionProfile profile) {
		var r = new RedisResource(ResourceKind.CONNECTION);
		r.profileId = profile.getId();
		r.readOnly = profile.isReadOnly();
		r.setUuid(SCHEME + profile.getId());
		r.setFullPath(profile.getName());
		r.setName(profile.getName());
		r.setFolder(true);
		r.getMetadata().put("Name", profile.getName());
		r.getMetadata().put("Address", profile.getDisplayTarget());
		return r;
	}

	public static RedisResource database(String profileId, int db, boolean readOnly) {
		var r = new RedisResource(ResourceKind.DATABASE);
		r.profileId = profileId;
		r.db = db;
		r.readOnly = readOnly;
		r.bytes = new byte[0];
		r.setUuid(SCHEME + profileId + "/" + db);
		r.setFullPath("db" + db);
		r.setName("db" + db);
		r.setFolder(true);
		r.getMetadata().put("Name", "db" + db);
		return r;
	}

	public static RedisResource database(String profileId, int db, boolean readOnly, long keys) {
		var r = database(profileId, db, readOnly);
		r.setLength(keys);
		r.getMetadata().put("Keys", keys > 0 ? String.format(Locale.ROOT, "%,d", keys) : "");
		return r;
	}

	/** @param countPartial whether the listing stopped at the scan limit, so the count is a floor */
	public static RedisResource folder(String profileId, int db, boolean readOnly, KeyTree.Folder folder,
			boolean countPartial) {
		var r = folder(profileId, db, readOnly, folder.prefix(), KeyNames.display(folder.segment()));
		r.setLength(folder.keys());
		r.countPartial = countPartial;
		String count = String.format(Locale.ROOT, "%,d", folder.keys());
		r.getMetadata().put("Size", countPartial ? tr("size.keys.partial", count)
				: folder.keys() == 1 ? tr("size.keys.one") : tr("size.keys.many", count));
		return r;
	}

	public static RedisResource folder(String profileId, int db, boolean readOnly, byte[] prefix, String segmentName) {
		var r = new RedisResource(ResourceKind.FOLDER);
		r.profileId = profileId;
		r.db = db;
		r.readOnly = readOnly;
		r.bytes = prefix;
		String name = segmentName.isEmpty() ? tr("name.empty") : segmentName;
		r.setUuid(SCHEME + profileId + "/" + db + "/f/" + encode(prefix));
		r.setFullPath("db" + db + "/" + KeyNames.display(prefix));
		r.setName(name);
		r.setFolder(true);
		r.getMetadata().put("Name", name);
		r.getMetadata().put("Type", "");
		r.getMetadata().put("TTL", "");
		return r;
	}

	/**
	 * A key row. {@code prefix} is the folder it is listed in: the row is named by the
	 * rest, as a file is by its name without the directory.
	 */
	public static RedisResource key(String profileId, int db, boolean readOnly, byte[] prefix, KeyInfo info) {
		var r = new RedisResource(ResourceKind.KEY);
		r.profileId = profileId;
		r.db = db;
		r.readOnly = readOnly;
		r.bytes = info.key();
		r.keyType = info.type();
		r.typeName = info.typeName();
		r.ttlMillis = info.ttlMillis();
		int from = prefix == null || !KeyNames.startsWith(info.key(), prefix) ? 0 : prefix.length;
		String name = KeyNames.display(java.util.Arrays.copyOfRange(info.key(), from, info.key().length));
		if (name.isEmpty()) {
			name = tr("name.empty");
		}
		r.setUuid(SCHEME + profileId + "/" + db + "/k/" + encode(info.key()));
		r.setFullPath("db" + db + "/" + KeyNames.display(info.key()));
		r.setName(name);
		r.setFolder(false);
		r.setLength(Math.max(0, info.length()));
		r.getMetadata().put("Name", name);
		r.getMetadata().put("Type", info.type() == KeyType.OTHER ? String.valueOf(info.typeName()) : info.type().redisName());
		r.getMetadata().put("Size", sizeText(info.type(), info.length()));
		r.getMetadata().put("TTL", ttlText(info.ttlMillis()));
		return r;
	}

	public static RedisResource limitMarker(String profileId, int db, byte[] prefix, String text) {
		var r = new RedisResource(ResourceKind.LIMIT_MARKER);
		r.profileId = profileId;
		r.db = db;
		r.bytes = prefix;
		r.setUuid(SCHEME + profileId + "/" + db + "/#limit/" + encode(prefix));
		r.setFullPath("");
		r.setName(text);
		r.setFolder(false);
		r.setReadable(false);
		r.getMetadata().put("Name", text);
		return r;
	}

	public static RedisResource parentEntry(RedisResource parent) {
		var r = new RedisResource(parent.kind);
		r.profileId = parent.profileId;
		r.db = parent.db;
		r.readOnly = parent.readOnly;
		r.bytes = parent.bytes;
		r.countPartial = parent.countPartial;
		r.setUuid(parent.getUuid());
		r.setFullPath(parent.getFullPath());
		r.setName("..");
		r.setFolder(true);
		return r;
	}

	private static String encode(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	// ------------------------------------------------------------------
	// Column texts
	// ------------------------------------------------------------------

	public static String sizeText(KeyType type, long length) {
		if (length < 0) {
			return "";
		}
		String count = String.format(Locale.ROOT, "%,d", length);
		return switch (type) {
			case STRING -> humanSize(length);
			case HASH -> length == 1 ? tr("size.fields.one") : tr("size.fields.many", count);
			case STREAM -> length == 1 ? tr("size.entries.one") : tr("size.entries.many", count);
			case SET, ZSET -> length == 1 ? tr("size.members.one") : tr("size.members.many", count);
			case LIST -> length == 1 ? tr("size.items.one") : tr("size.items.many", count);
			default -> "";
		};
	}

	/** {@code 45s}, {@code 12m 5s}, {@code 3h 20m}, {@code 2d 4h}; blank for no expiry. */
	public static String ttlText(long millis) {
		if (millis < 0) {
			return "";
		}
		long seconds = (millis + 999) / 1000;
		if (seconds < 60) {
			return seconds + "s";
		}
		long minutes = seconds / 60;
		if (minutes < 60) {
			return minutes + "m " + seconds % 60 + "s";
		}
		long hours = minutes / 60;
		if (hours < 24) {
			return hours + "h " + minutes % 60 + "m";
		}
		return hours / 24 + "d " + hours % 24 + "h";
	}

	public static String humanSize(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		String[] units = { "KB", "MB", "GB", "TB" };
		double value = bytes;
		int unit = -1;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
	}
}
