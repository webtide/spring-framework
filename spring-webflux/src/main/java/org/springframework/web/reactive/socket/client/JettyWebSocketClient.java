/*
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.reactive.socket.client;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.JettyUpgradeListener;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.springframework.context.Lifecycle;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.Nullable;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.adapter.JettyWebSocketHandlerAdapter;
import org.springframework.web.reactive.socket.adapter.JettyWebSocketSession;

public class JettyWebSocketClient implements WebSocketClient, Lifecycle {

	private final org.eclipse.jetty.websocket.client.WebSocketClient client;

	public JettyWebSocketClient() {
		this.client = new org.eclipse.jetty.websocket.client.WebSocketClient();
	}

	@Override
	public void start() {
		LifeCycle.start(this.client);
	}

	@Override
	public void stop() {
		LifeCycle.stop(this.client);
	}

	@Override
	public boolean isRunning() {
		return false;
	}

	@Override
	public Mono<Void> execute(URI url, WebSocketHandler handler) {
		return execute(url, null, handler);
	}

	@Override
	public Mono<Void> execute(URI url, @Nullable HttpHeaders headers, WebSocketHandler handler) {

		ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
		upgradeRequest.setSubProtocols(handler.getSubProtocols());
		if (headers != null) {
			headers.keySet().forEach(header -> upgradeRequest.setHeader(header, headers.getValuesAsList(header)));
		}

		AtomicReference<HandshakeInfo> handshakeInfo = new AtomicReference<>();
		JettyUpgradeListener jettyUpgradeListener = new JettyUpgradeListener() {
			@Override
			public void onHandshakeResponse(Request request, Response response) {
				String protocol = response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_SUBPROTOCOL);
				HttpHeaders responseHeaders = new HttpHeaders();
				response.getHeaders().forEach(header -> responseHeaders.add(header.getName(), header.getValue()));
				handshakeInfo.set(new HandshakeInfo(url, responseHeaders, Mono.empty(), protocol));
			}
		};

		Sinks.Empty<Void> completion = Sinks.empty();
		JettyWebSocketHandlerAdapter handlerAdapter = new JettyWebSocketHandlerAdapter(handler, session ->
				new JettyWebSocketSession(session, handshakeInfo.get(), DefaultDataBufferFactory.sharedInstance, completion));
		try {
			this.client.connect(handlerAdapter, url, upgradeRequest, jettyUpgradeListener)
					.whenComplete((session, throwable) -> {
						// Only fail the completion if we have an error
						// as the JettyWebSocketSession will never be opened.
						if (throwable != null) {
							completion.tryEmitError(throwable);
						}
					});
			return completion.asMono();
		}
		catch (IOException ex) {
			return Mono.error(ex);
		}
	}
}
