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

import java.io.Serializable;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A saved Redis connection's non-secret settings, persisted as JSON by
 * {@link ConnectionProfileStore}. The password is never a field here: it lives in the OS
 * credential store (see {@link CredentialStore}) or is asked for on connect.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedisConnectionProfile implements Serializable {

	private static final long serialVersionUID = 1L;

	public static final int DEFAULT_PORT = 6379;
	public static final String DEFAULT_SEPARATOR = ":";

	private String id;
	private String name;
	private String host;

	@Builder.Default
	private int port = DEFAULT_PORT;

	/** An ACL user (Redis 6+); blank authenticates as {@code default}. */
	private String username;

	/** The server wants a password (requirepass or an ACL user's). */
	private boolean auth;

	private boolean tls;

	/** What splits a key into folders; {@code ":"} unless the application uses another. */
	@Builder.Default
	private String separator = DEFAULT_SEPARATOR;

	/** Hides F4 and F8 on keys, so a production cache can be browsed without risk. */
	private boolean readOnly;

	@JsonIgnore
	public String getEffectiveSeparator() {
		return separator == null || separator.isEmpty() ? DEFAULT_SEPARATOR : separator;
	}

	@JsonIgnore
	public int getEffectivePort() {
		return port > 0 && port <= 65535 ? port : DEFAULT_PORT;
	}

	@JsonIgnore
	public String getDisplayTarget() {
		String target = (host == null ? "" : host) + ":" + getEffectivePort();
		return tls ? target + " (TLS)" : target;
	}

	/** Whether a password has to be found (stored or asked for) before connecting. */
	@JsonIgnore
	public boolean needsSecret() {
		return auth;
	}
}
