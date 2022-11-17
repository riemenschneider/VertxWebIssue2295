import java.io.File;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.internal.PlatformDependent;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Repeat;
import io.vertx.ext.unit.junit.RepeatRule;
import io.vertx.ext.unit.junit.RunTestOnContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.LoggerHandler;

@RunWith(VertxUnitRunner.class)
public class TestClass {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(TestClass.class);
	
	private static final int HTTP_SERVER_PORT = Integer.getInteger("HTTP_SERVER_PORT", 9090);
	private static final int LOADBALANCER_PORT = Integer.getInteger("LOADBALANCER_PORT", 9091);
	private static final boolean LOG_ACTIVITY = Boolean.getBoolean("LOG_ACTIVITY");
	
	private static Future<HttpServer> startVertxHttpServer(Vertx vertx, int port, boolean logActivity) {
		LOGGER.info("Starting Vert.x HttpServer.");
		HttpServer httpServer = vertx.createHttpServer(new HttpServerOptions()
				.setHost("0.0.0.0")
				.setPort(port)
				.setLogActivity(logActivity));
		Router router = Router.router(vertx);
		router.route()
				.handler(LoggerHandler.create())
				.handler(BodyHandler.create()
						.setBodyLimit(1024 * 1024))
				.handler(routingContext -> routingContext.response()
						.end());
		return httpServer.requestHandler(router)
				.listen()
				.onSuccess(ignore -> LOGGER.info("Vert.x HttpServer successfully started."))
				.onFailure(cause -> LOGGER.error("Failed to start Vert.x HttpServer.", cause));
	}
	
	private static Future<String> startNginxLoadbalancer(Vertx vertx, int loadbalancerPort, int serverPort) {
		return vertx.<String>executeBlocking(promise -> {
			LOGGER.info("Starting Nginx loadbalancer.");
			try {
				Path nginxConfTempFilePath = File.createTempFile("nginx", ".conf").toPath();
				List<String> nginxConf = Arrays.asList(
					"events { }",
					"",
					"http {",
					"  server {",
					"    listen 0.0.0.0:" + loadbalancerPort + ";",
					"    ",
					"    location / {",
					"      proxy_pass http://backend;",
					"    }",
					"  }",
					"  ",
					"  upstream backend {",
					"    server host.docker.internal:" + serverPort + ";",
					"  }",
					"  ",
					"  client_max_body_size 10M;",
					"}"
				);
				LOGGER.info("Using nginx.conf:\n{}", String.join("\n", nginxConf));
				Files.write(nginxConfTempFilePath, nginxConf, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
				File outputTempFile = File.createTempFile("output", null);
				File errorTempFile = File.createTempFile("error", null);
				String[] command = PlatformDependent.isWindows() || PlatformDependent.isOsx()
						? new String[] { "docker", "run", "--rm", "--detach", "--publish", loadbalancerPort + ":" + loadbalancerPort, "--volume", nginxConfTempFilePath + ":/etc/nginx/nginx.conf:ro", "nginx" }
						: new String[] { "docker", "run", "--rm", "--detach", "--add-host", "host.docker.internal:host-gateway", "--publish", loadbalancerPort + ":" + loadbalancerPort, "--volume", nginxConfTempFilePath + ":/etc/nginx/nginx.conf:ro", "nginx" };
				LOGGER.info("Using command '{}'", String.join(" ", command));
				Process process = new ProcessBuilder()
						.command(command)
						.redirectOutput(outputTempFile)
						.redirectError(errorTempFile)
						.start();
				if (process.waitFor() == 0) {
					LOGGER.info("Successfully started Nginx loadbalancer.");
					promise.complete(Files.readString(outputTempFile.toPath(), StandardCharsets.UTF_8).strip());
				}
				else {
					LOGGER.error("Failed to start Nginx loadbalancer. Process returned with exit code {}.", process.exitValue());
					LOGGER.error(Files.readString(errorTempFile.toPath(), StandardCharsets.UTF_8).strip());
					promise.fail(process.info().toString());
				}
			}
			catch (Throwable cause) {
				LOGGER.error("Failed to start Nginx loadbalancer.", cause);
				promise.fail(cause);
			}
		});
	}
	
	private static Future<String> waitForInitializationOfNginxLoadbalancer(Vertx vertx, String containerId) {
		return vertx.executeBlocking(promise -> {
			LOGGER.info("Waiting for initialization of Nginx loadbalancer.");
			try {
				File outputTempFile = File.createTempFile("output", null);
				File errorTempFile = File.createTempFile("error", null);
				for (Future<String> future = promise.future(); !future.isComplete(); ) {
					Process process = new ProcessBuilder()
							.command("docker", "logs", containerId)
							.redirectOutput(outputTempFile)
							.redirectError(errorTempFile)
							.start();
					if (process.waitFor() == 0) {
						if (Files.readString(outputTempFile.toPath(), StandardCharsets.UTF_8).contains("Configuration complete; ready for start up")) {
							LOGGER.info("Nginx loadbalancer is up and running.");
							promise.complete(containerId);
						}
					}
					else {
						LOGGER.error("Failed to wait for initialization of Nginx loadbalancer. Process returned with exit code {}.", process.exitValue());
						LOGGER.error(Files.readString(errorTempFile.toPath(), StandardCharsets.UTF_8).strip());
						promise.fail(process.info().toString());
					}
				}
			}
			catch (Throwable cause) {
				LOGGER.error("Failed to wait for initialization of Nginx loadbalancer.", cause);
				promise.fail(cause);
			}
		});
	}
	
	private static Future<Void> stopNginxLoadbalancer(Vertx vertx, String containerId) {
		return vertx.executeBlocking(promise -> {
			LOGGER.info("Stopping Nginx loadbalancer.");
			try {
				File outputTempFile = File.createTempFile("output", null);
				new ProcessBuilder()
						.command("docker", "logs", containerId)
						.redirectOutput(outputTempFile)
						.redirectError(Redirect.DISCARD)
						.start()
						.waitFor();
				String logs = Files.readString(outputTempFile.toPath(), StandardCharsets.UTF_8);
				if (!logs.isBlank())
					LOGGER.info("Logs from Nginx loadbalancer:\n{}", logs);
				outputTempFile = File.createTempFile("output", null);
				File errorTempFile = File.createTempFile("error", null);
				Process process = new ProcessBuilder()
						.command("docker", "stop", containerId)
						.redirectOutput(outputTempFile)
						.redirectError(errorTempFile)
						.start();
				if (process.waitFor() == 0) {
					LOGGER.info("Nginx loadbalancer successfully stopped.");
					promise.complete();
				}
				else {
					LOGGER.error("Failed to stop Nginx loadbalancer. Process returned with exit code {}.", process.exitValue());
					LOGGER.error(Files.readString(errorTempFile.toPath(), StandardCharsets.UTF_8).strip());
					promise.fail(process.info().toString());
				}
			}
			catch (Throwable cause) {
				LOGGER.error("Failed to stop Nginx loadbalancer.", cause);
				promise.fail(cause);
			}
		});
	}
	
	private static class StatusCodes {
		
		private List<Integer> list;
		
		public StatusCodes() {
			list = new ArrayList<>();
		}
		
		public StatusCodes(Integer... statusCodes) {
			this();
			Collections.addAll(list, statusCodes);
		}
		
		public StatusCodes add(int statusCode) {
			list.add(statusCode);
			return this;
		}
		
		public boolean equals(Object other) {
			if (this == other)
				return true;
			if (other == null || !(other instanceof StatusCodes))
				return false;
			return list.equals(((StatusCodes)other).list);
		}
		
		public String toString() {
			return list.toString();
		}
		
	}
	
	private static Future<StatusCodes> runWebClient(Vertx vertx, int port, boolean logActivity, TestContext context) {
		LOGGER.info("Running Vert.x WebCient.");
		HttpClient httpClient = vertx.createHttpClient(new HttpClientOptions()
				.setLogActivity(logActivity));
		WebClient webClient = WebClient.wrap(httpClient, new WebClientOptions()
				.setLogActivity(logActivity));
		return Future.succeededFuture(new StatusCodes())
				.compose(statusCodes -> webClient.post(port, "localhost", "/3")
						.send()
						.map(HttpResponse::statusCode)
						.map(statusCodes::add))
				.compose(statusCodes -> webClient.post(port, "localhost", "/2")
						.send()
						.map(HttpResponse::statusCode)
						.map(statusCodes::add))
				.compose(statusCodes -> webClient.post(port, "localhost", "/1")
						.send()
						.map(HttpResponse::statusCode)
						.map(statusCodes::add))
				.compose(statusCodes -> webClient.post(port, "localhost", "/test")
						.sendBuffer(Buffer.buffer(new byte[1024 * 1024 + 42]))
						.map(HttpResponse::statusCode)
						.map(statusCodes::add))
				.compose(statusCodes -> webClient.post(port, "localhost", "/1")
						.send()
						.map(HttpResponse::statusCode)
						.map(statusCodes::add))
				.compose(statusCodes -> webClient.post(port, "localhost", "/2")
						.send()
						.map(HttpResponse::statusCode)
						.map(statusCodes::add))
				.compose(statusCodes -> webClient.post(port, "localhost", "/3")
						.send()
						.map(HttpResponse::statusCode)
						.map(statusCodes::add))
				.eventually(ignore -> httpClient.close());
	}
	
	@Rule
	public RunTestOnContext runTestOnContext = new RunTestOnContext();
	
	@Rule
	public RepeatRule repeatRule = new RepeatRule();
	
	@Test
	@Repeat(1000)
	public void test(TestContext context) {
		Vertx vertx = runTestOnContext.vertx();
		Future<HttpServer> httpServerFuture = startVertxHttpServer(vertx, HTTP_SERVER_PORT, LOG_ACTIVITY);
		Future<String> loadbalancerFuture = startNginxLoadbalancer(vertx, LOADBALANCER_PORT, HTTP_SERVER_PORT);
		CompositeFuture.all(httpServerFuture, loadbalancerFuture.compose(containerId -> waitForInitializationOfNginxLoadbalancer(vertx, containerId)))
				.compose(futures -> runWebClient(vertx, LOADBALANCER_PORT, LOG_ACTIVITY, context))
				.eventually(ignore -> CompositeFuture.join(httpServerFuture.compose(HttpServer::close), loadbalancerFuture.compose(containerId -> stopNginxLoadbalancer(vertx, containerId))))
				.onComplete(context.asyncAssertSuccess(statusCodes -> context.assertEquals(new StatusCodes(200, 200, 200, 413, 200, 200, 200), statusCodes)));
	}

}
