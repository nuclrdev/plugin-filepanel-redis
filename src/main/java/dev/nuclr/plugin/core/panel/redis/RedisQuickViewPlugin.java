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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import dev.nuclr.plugin.core.panel.redis.quickview.ValuePanel;
import dev.nuclr.plugin.core.panel.redis.resource.RedisResource;

/** Ctrl+Q companion to {@link RedisFilePanelPlugin}: the value of the key under the cursor. */
public final class RedisQuickViewPlugin implements QuickViewNuclrPlugin {

	private final String uuid = UUID.randomUUID().toString();

	private NuclrPluginContext context;
	private volatile boolean focused;
	private volatile NuclrResource currentResource;
	private ValuePanel view;

	@Override
	public void preinit(NuclrPluginContext context) {
		if (context != null) {
			Messages.use(context.getLocale());
		}
		this.context = context;
	}

	@Override
	public void init() {
	}

	@Override
	public void unload() {
		closeResource();
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	@Override
	public void closeResource() {
		currentResource = null;
		if (view != null) {
			SwingUtilities.invokeLater(view::clear);
		}
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	/** A key of a type this viewer can show, still attached to the panel that listed it. */
	@Override
	public boolean supports(NuclrResource resource) {
		return resource instanceof RedisResource redis && redis.isKey() && redis.canRead();
	}

	@Override
	public JComponent panel() {
		if (view == null) {
			view = new ValuePanel();
		}
		return view;
	}

	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (!supports(resource)) {
			return false;
		}
		var key = (RedisResource) resource;
		currentResource = key;
		panel();
		SwingUtilities.invokeLater(() -> view.show(key, cancelled));
		return true;
	}
}
