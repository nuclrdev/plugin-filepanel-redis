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
package dev.nuclr.plugin.core.panel.redis.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.plugin.core.panel.redis.PluginIds;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads/writes the saved {@link RedisConnectionProfile} list through {@code NuclrSettings}
 * (a plain {@code (namespace, key) -> Object} store). Serialized as a single JSON
 * string under one key rather than relying on however the host round-trips an
 * arbitrary object graph; {@code NuclrSettings} only promises the value is
 * "serialisable", and every plugin already carries Jackson as a {@code provided}
 * dependency, so this is the most portable choice.
 */
@Slf4j
public final class ConnectionProfileStore {

	private static final String KEY_PROFILES_JSON = "profiles.json";

	private final NuclrSettings settings;
	private final ObjectMapper mapper = new ObjectMapper();

	public ConnectionProfileStore(NuclrSettings settings) {
		this.settings = settings;
	}

	public synchronized List<RedisConnectionProfile> loadAll() {
		String json = settings.get(PluginIds.PLUGIN_ID, KEY_PROFILES_JSON);
		if (json == null || json.isBlank()) {
			return new ArrayList<>();
		}
		try {
			RedisConnectionProfile[] profiles = mapper.readValue(json, RedisConnectionProfile[].class);
			var list = new ArrayList<RedisConnectionProfile>(profiles.length);
			for (RedisConnectionProfile p : profiles) {
				list.add(p);
			}
			return list;
		} catch (Exception e) {
			log.error("Failed to parse saved Redis connection profiles; starting with an empty list.", e);
			return new ArrayList<>();
		}
	}

	public synchronized void saveAll(List<RedisConnectionProfile> profiles) {
		try {
			String json = mapper.writeValueAsString(profiles);
			settings.set(PluginIds.PLUGIN_ID, KEY_PROFILES_JSON, json);
		} catch (Exception e) {
			log.error("Failed to save Redis connection profiles.", e);
		}
	}

	public synchronized RedisConnectionProfile findById(String id) {
		return loadAll().stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
	}

	public synchronized RedisConnectionProfile add(RedisConnectionProfile profile) {
		if (profile.getId() == null || profile.getId().isBlank()) {
			profile.setId(UUID.randomUUID().toString());
		}
		var all = loadAll();
		all.add(profile);
		saveAll(all);
		return profile;
	}

	public synchronized void update(RedisConnectionProfile profile) {
		var all = loadAll();
		all.replaceAll(p -> p.getId().equals(profile.getId()) ? profile : p);
		saveAll(all);
	}

	public synchronized void delete(String id) {
		var all = loadAll();
		all.removeIf(p -> p.getId().equals(id));
		saveAll(all);
	}
}
