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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.nuclr.plugin.core.panel.redis.client.JedisSession;
import dev.nuclr.plugin.core.panel.redis.client.RedisSession;

/**
 * One {@link RedisSession} per connection, kept while the plugin is loaded: the pool
 * keeps its connections open, and the password is asked for (or read from the keychain)
 * once rather than on every folder.
 */
public final class ConnectionRegistry {

	/** Opens a session for a profile; a seam so tests can answer from memory. */
	@FunctionalInterface
	public interface SessionFactory {
		RedisSession open(RedisConnectionProfile profile, String password) throws Exception;
	}

	private final Map<String, RedisSession> sessions = new ConcurrentHashMap<>();
	private final Map<String, Object> locks = new ConcurrentHashMap<>();
	private final SessionFactory factory;

	public ConnectionRegistry() {
		this(JedisSession::open);
	}

	public ConnectionRegistry(SessionFactory factory) {
		this.factory = factory;
	}

	public RedisSession get(String profileId) {
		return sessions.get(profileId);
	}

	public boolean isConnected(String profileId) {
		return sessions.containsKey(profileId);
	}

	/**
	 * The lock first use of a connection holds, from looking for a session through
	 * asking for the password to connecting, so two callers at once share one session.
	 */
	public Object lockFor(String profileId) {
		return locks.computeIfAbsent(profileId, id -> new Object());
	}

	/**
	 * The kept session for {@code profile}, or a new one checked with a {@code PING}, so
	 * an unreachable server or a refused password fails here rather than in a listing.
	 */
	public RedisSession connect(RedisConnectionProfile profile, String password) throws Exception {
		synchronized (lockFor(profile.getId())) {
			RedisSession existing = sessions.get(profile.getId());
			if (existing != null) {
				return existing;
			}
			RedisSession session = open(profile, password);
			try {
				checkSupported(session.ping());
			} catch (RuntimeException e) {
				session.close();
				throw e;
			}
			sessions.put(profile.getId(), session);
			return session;
		}
	}

	/**
	 * A cluster spreads keys over nodes and has only database 0, so listing one node would
	 * quietly show part of the keys. Better to say so up front.
	 */
	public static void checkSupported(RedisSession.ServerInfo info) {
		if (info != null && "cluster".equalsIgnoreCase(info.mode())) {
			throw new UnsupportedOperationException(
					"This server is a Redis Cluster node; Redis Explorer browses standalone servers and replicas.");
		}
	}

	/** A session not kept anywhere, for the editor's Test Connection. The caller closes it. */
	public RedisSession open(RedisConnectionProfile profile, String password) throws Exception {
		return factory.open(profile, password);
	}

	public void disconnect(String profileId) {
		RedisSession session = sessions.remove(profileId);
		if (session != null) {
			session.close();
		}
	}

	public void disconnectAll() {
		sessions.keySet().forEach(this::disconnect);
	}
}
