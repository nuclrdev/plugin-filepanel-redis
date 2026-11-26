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
package dev.nuclr.plugin.core.panel.redis.transfer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import dev.nuclr.plugin.core.panel.redis.connection.ConnectionProfileStore;
import dev.nuclr.plugin.core.panel.redis.connection.CredentialStore;
import dev.nuclr.plugin.core.panel.redis.connection.RedisConnectionProfile;
import dev.nuclr.plugin.core.panel.redis.connection.RedisUri;
import lombok.extern.slf4j.Slf4j;

/**
 * This plugin's side of Commander's Configs - Export / Import, spoken over {@code act}
 * with plain maps (Commander's {@code TransferProtocol}; see
 * {@code commander/docs/settings-transfer-plan.md}, section 4). Saved connections are the
 * {@code connections} category.
 *
 * <p>The only secret is the password, kept in the keychain under the profile id. The
 * connection editor turns a pasted {@code redis://user:password@host} into separate fields,
 * so a saved host never carries one; a hand-edited or crafted host still might, so a
 * snapshot strips it out of the host, and apply strips it again - a crafted file cannot put
 * a password into {@code settings.json}. Apply never writes the keychain; what it strips
 * goes back to Commander as a recovered secret, which Commander stores under the new id.
 *
 * <p>Called off the EDT, on the instance Commander keeps for actions.
 */
@Slf4j
public final class RedisTransfer {

	public static final String Describe = "transfer.describe";
	public static final String Snapshot = "transfer.snapshot";
	public static final String ReadSecrets = "transfer.readSecrets";
	public static final String FindEmbeddedSecrets = "transfer.findEmbeddedSecrets";
	public static final String Apply = "transfer.apply";
	public static final String Remove = "transfer.remove";

	static final int ProtocolVersion = 1;
	static final String Type = "connections";
	static final int SectionVersion = 1;

	// Field names, as they appear in the export file.
	static final String Name = "name";
	static final String Host = "host";
	static final String Port = "port";
	static final String Username = "username";
	static final String Auth = "auth";
	static final String Tls = "tls";
	static final String Separator = "separator";
	static final String ReadOnly = "readOnly";
	static final String Password = "password";

	private final ConnectionProfileStore profiles;
	private final CredentialStore credentials;

	/** The rows of the last snapshot by row id, so an export reads secrets for exactly those. */
	private Map<String, RedisConnectionProfile> lastSnapshot = Map.of();

	public RedisTransfer(ConnectionProfileStore profiles, CredentialStore credentials) {
		this.profiles = profiles;
		this.credentials = credentials;
	}

	public static boolean handles(String action) {
		return switch (action) {
			case Describe, Snapshot, ReadSecrets, FindEmbeddedSecrets, Apply, Remove -> true;
			default -> false;
		};
	}

	/**
	 * Answer one transfer action into {@code data}. Throws on failure; Commander treats a
	 * throw as "the plugin failed" and, for apply, relies on the store being unchanged.
	 */
	public void handle(String action, Map<String, Object> data) {
		if (!(data.get("transfer.protocol") instanceof Integer version) || version != ProtocolVersion) {
			return; // a protocol this build does not speak: do not take part
		}
		if (!Describe.equals(action) && !Type.equals(data.get("type"))) {
			return;
		}
		switch (action) {
			case Describe -> data.put("transfer.providers", List.of(describe()));
			case Snapshot -> data.put("rows", snapshot());
			case ReadSecrets -> data.put("secrets", readSecrets(String.valueOf(data.get("rowId"))));
			case FindEmbeddedSecrets -> data.put("fieldNames", findEmbeddedSecrets(strings(data.get("fields"))));
			case Apply -> {
				Map<String, Object> recovered = apply(items(data.get("items")));
				if (!recovered.isEmpty()) {
					data.put("recoveredSecrets", recovered);
				}
			}
			case Remove -> remove(ids(data.get("ids")));
			default -> {
			}
		}
	}

	static Map<String, Object> describe() {
		var fields = new ArrayList<Map<String, Object>>();
		fields.add(field(Name, "PLAIN"));
		fields.add(field(Host, "ROUTING"));
		fields.add(field(Port, "ROUTING"));
		fields.add(field(Username, "PLAIN"));
		fields.add(field(Auth, "PLAIN"));
		fields.add(field(Tls, "PLAIN"));
		fields.add(field(Separator, "PLAIN"));
		fields.add(field(ReadOnly, "PLAIN"));
		fields.add(secret(Password, ""));
		return Map.of("type", Type, "version", SectionVersion, "title", "Redis", "fields", fields);
	}

	List<Map<String, Object>> snapshot() {
		var rows = new ArrayList<Map<String, Object>>();
		var byRow = new HashMap<String, RedisConnectionProfile>();
		int index = 0;
		for (RedisConnectionProfile profile : profiles.loadAll()) {
			index++;
			// An entry saved without an id keeps a row of its own; it just has no stored secrets.
			String rowId = profile.getId() != null && !profile.getId().isBlank() ? profile.getId() : "#" + index;
			byRow.put(rowId, profile);
			var row = new HashMap<String, Object>();
			row.put("rowId", rowId);
			row.put("label", profile.getName() == null || profile.getName().isBlank() ? rowId : profile.getName());
			row.put("fields", new LinkedHashMap<String, Object>(fields(profile)));
			row.put("hasSecrets", !secrets(profile).isEmpty());
			rows.add(row);
		}
		lastSnapshot = byRow;
		return rows;
	}

	Map<String, String> readSecrets(String rowId) {
		RedisConnectionProfile profile = lastSnapshot.get(rowId);
		if (profile == null) {
			profile = profiles.loadAll().stream().filter(p -> Objects.equals(p.getId(), rowId)).findFirst()
					.orElse(null);
		}
		return profile == null ? Map.of() : secrets(profile);
	}

	static List<String> findEmbeddedSecrets(Map<String, String> fields) {
		return stripHost(new HashMap<>(fields)) != null ? List.of(Host) : List.of();
	}

	/** Add every item under its given id in one write; return the passwords stripped from hosts on the way. */
	Map<String, Object> apply(List<Map<String, Object>> items) {
		var added = new ArrayList<RedisConnectionProfile>();
		var recovered = new LinkedHashMap<String, Object>();
		for (Map<String, Object> item : items) {
			String id = item.get("newId") instanceof String text ? text : null;
			if (id == null || id.isBlank()) {
				throw new IllegalArgumentException("An imported connection has no id");
			}
			Map<String, String> fields = strings(item.get("fields"));
			String password = stripHost(fields);
			if (password != null) {
				recovered.put(id, Map.of(Password, password));
			}
			RedisConnectionProfile profile = RedisConnectionProfile.builder()
					.id(id)
					.name(blankTo(fields.get(Name), blankTo(fields.get(Host), "Imported connection")))
					.host(fields.get(Host))
					.port(port(fields.get(Port)))
					.username(blankTo(fields.get(Username), null))
					.auth(Boolean.parseBoolean(fields.get(Auth)))
					.tls(Boolean.parseBoolean(fields.get(Tls)))
					.separator(fields.get(Separator) == null || fields.get(Separator).isEmpty()
							? RedisConnectionProfile.DEFAULT_SEPARATOR
							: fields.get(Separator))
					.readOnly(Boolean.parseBoolean(fields.get(ReadOnly)))
					.build();
			added.add(profile);
		}
		profiles.addAll(added);
		return recovered;
	}

	void remove(List<String> ids) {
		profiles.removeAll(new HashSet<>(ids));
	}

	/** A profile's non-secret fields, with any password left in the host stripped out. */
	static Map<String, String> fields(RedisConnectionProfile profile) {
		var fields = new LinkedHashMap<String, String>();
		put(fields, Name, profile.getName());
		put(fields, Host, profile.getHost());
		fields.put(Port, String.valueOf(profile.getPort()));
		put(fields, Username, profile.getUsername());
		fields.put(Auth, String.valueOf(profile.isAuth()));
		fields.put(Tls, String.valueOf(profile.isTls()));
		if (profile.getSeparator() != null && !profile.getSeparator().isEmpty()) {
			fields.put(Separator, profile.getSeparator());
		}
		fields.put(ReadOnly, String.valueOf(profile.isReadOnly()));
		stripHost(fields);
		return fields;
	}

	/**
	 * Take credentials out of the host, the way the connection editor does when a URL is
	 * pasted: a whole {@code redis://} or {@code rediss://} URL fills host, port, TLS and
	 * user; a bare {@code user:password@host} loses its user part. Changes {@code fields} in
	 * place.
	 *
	 * @return the password that was in the host, or {@code null} when there was none
	 */
	static String stripHost(Map<String, String> fields) {
		String host = fields.get(Host);
		if (host == null || host.isBlank()) {
			return null;
		}
		String username;
		String password;
		RedisUri uri = RedisUri.parse(host);
		if (uri != null) {
			fields.put(Host, uri.host());
			fields.put(Port, String.valueOf(uri.port()));
			fields.put(Tls, String.valueOf(uri.tls()));
			username = uri.username();
			password = uri.password();
		} else {
			int at = host.lastIndexOf('@');
			if (at < 0) {
				return null;
			}
			// No host name has an '@'; whatever comes before it is a user and password,
			// read like RedisUri reads one (a lone part is the old requirepass form).
			String userInfo = host.substring(0, at).strip();
			String rest = host.substring(at + 1).strip();
			fields.put(Host, rest.isEmpty() ? "localhost" : rest);
			int colon = userInfo.indexOf(':');
			username = colon >= 0 ? emptyToNull(userInfo.substring(0, colon)) : null;
			password = emptyToNull(colon >= 0 ? userInfo.substring(colon + 1) : userInfo);
		}
		if (username != null || password != null) {
			fields.put(Auth, "true");
		}
		if (username != null) {
			fields.put(Username, username);
		}
		return password == null || password.isBlank() ? null : password;
	}

	private static String emptyToNull(String text) {
		return text == null || text.isEmpty() ? null : text;
	}

	/** The stored password, or failing that one still left in the host. */
	private Map<String, String> secrets(RedisConnectionProfile profile) {
		var secrets = new LinkedHashMap<String, String>();
		String id = profile.getId();
		String password = id != null && !id.isBlank() ? credentials.loadPassword(id) : null;
		if (password == null || password.isBlank()) {
			var fields = new HashMap<String, String>();
			put(fields, Host, profile.getHost());
			password = stripHost(fields);
		}
		if (password != null && !password.isBlank()) {
			secrets.put(Password, password);
		}
		return secrets;
	}

	private static int port(String text) {
		try {
			int port = Integer.parseInt(text == null ? "" : text.strip());
			return port > 0 && port <= 65535 ? port : RedisConnectionProfile.DEFAULT_PORT;
		} catch (NumberFormatException e) {
			return RedisConnectionProfile.DEFAULT_PORT;
		}
	}

	private static Map<String, Object> field(String name, String kind) {
		return Map.of("name", name, "kind", kind);
	}

	private static Map<String, Object> secret(String name, String suffix) {
		return Map.of("name", name, "kind", "SECRET", "credentialSuffix", suffix);
	}

	private static void put(Map<String, String> fields, String name, String value) {
		if (value != null && !value.isBlank()) {
			fields.put(name, value);
		}
	}

	private static String blankTo(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static Map<String, String> strings(Object raw) {
		var map = new HashMap<String, String>();
		if (raw instanceof Map<?, ?> values) {
			values.forEach((key, value) -> {
				if (key instanceof String name && value instanceof String text) {
					map.put(name, text);
				}
			});
		}
		return map;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> items(Object raw) {
		var items = new ArrayList<Map<String, Object>>();
		if (raw instanceof List<?> list) {
			for (Object item : list) {
				if (item instanceof Map<?, ?> map) {
					items.add((Map<String, Object>) map);
				}
			}
		}
		return items;
	}

	private static List<String> ids(Object raw) {
		var ids = new ArrayList<String>();
		if (raw instanceof List<?> list) {
			for (Object id : list) {
				if (id instanceof String text) {
					ids.add(text);
				}
			}
		}
		return ids;
	}

	/** Field names, for tests. */
	static Set<String> fieldNames() {
		var names = new HashSet<String>();
		for (Object field : (List<?>) describe().get("fields")) {
			names.add((String) ((Map<?, ?>) field).get("name"));
		}
		return names;
	}
}
