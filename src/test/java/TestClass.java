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
	
	private static final int HTTP_SERVER_PORT = Integer.getInteger("HTTP_SERVER_PORT", 9090);
	private static final int LOADBALANCER_PORT = Integer.getInteger("LOADBALANCER_PORT", 9091);
	private static final boolean LOG_ACTIVITY = Boolean.getBoolean("LOG_ACTIVITY");
	
	public static Future<HttpServer> startVertxHttpServer(Vertx vertx, int port, boolean logActivity) {
		HttpServer httpServer = vertx.createHttpServer(new HttpServerOptions()
				.setHost("localhost")
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
				.listen();
	}
	
	public static Future<String> startNginxLoadbalancer(Vertx vertx, int loadbalancerPort, int serverPort) {
		return vertx.executeBlocking(promise -> {
			try {
				Path nginxConfTempFilePath = File.createTempFile("nginx", ".conf").toPath();
				List<String> nginxConf = Arrays.asList(
					"events { }",
					"",
					"http {",
					"  server {",
					"    listen " + loadbalancerPort + ";",
					"    ",
					"    location / {",
					"      proxy_pass http://backend;",
					"    }",
					"  }",
					"  ",
					"  upstream backend {",
					"    server host.docker.internal:" + serverPort + ";",
					"  }",
					"  client_max_body_size 10M;",
					"}"
				);
				Files.write(nginxConfTempFilePath, nginxConf, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
				File outputTempFile = File.createTempFile("docker", null);
				Process process = new ProcessBuilder()
						.command("docker", "run", "--rm", "--detach", "--publish", loadbalancerPort + ":" + loadbalancerPort, "--volume", nginxConfTempFilePath + ":/etc/nginx/nginx.conf:ro", "nginx")
						.redirectOutput(outputTempFile)
						.redirectError(Redirect.INHERIT)
						.start();
				if (process.waitFor() == 0)
					promise.complete(Files.readAllLines(outputTempFile.toPath()).get(0));
				else
					promise.fail("Command 'docker run' failed (" + process.exitValue() +").");
			}
			catch (Throwable t) {
				promise.fail(t);
			}
		});
	}
	
	public static Future<Void> stopNginxLoadbalancer(Vertx vertx, String containerId) {
		return vertx.executeBlocking(promise -> {
			try {
				System.out.println("### Logs from Nginx loadbalancer ###");
				new ProcessBuilder()
						.command("docker", "logs", containerId)
						.inheritIO()
						.start()
						.waitFor();
				System.out.println("### Logs from Nginx loadbalancer ###");
				Process process = new ProcessBuilder()
						.command("docker", "stop", containerId)
						.inheritIO()
						.start();
				if (process.waitFor() == 0)
					promise.complete();
				else
					promise.fail("Command 'docker stop' failed (" + process.exitValue() +").");
			}
			catch (Throwable t) {
				promise.fail(t);
			}
		});
	}
	
	public static class StatusCodes {
		
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
	
	public static Future<StatusCodes> runWebClient(Vertx vertx, int port, boolean logActivity, TestContext context) {
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
	@Repeat(10)
	public void test(TestContext context) {
		Vertx vertx = runTestOnContext.vertx();
		Future<HttpServer> httpServerFuture = startVertxHttpServer(vertx, HTTP_SERVER_PORT, LOG_ACTIVITY);
		Future<String> loadbalancerFuture = startNginxLoadbalancer(vertx, LOADBALANCER_PORT, HTTP_SERVER_PORT);
		CompositeFuture.all(httpServerFuture, loadbalancerFuture)
				.compose(futures -> runWebClient(vertx, LOADBALANCER_PORT, LOG_ACTIVITY, context))
				.eventually(ignore -> CompositeFuture.join(httpServerFuture.compose(HttpServer::close), loadbalancerFuture.compose(containerId -> stopNginxLoadbalancer(vertx, containerId))))
				.onComplete(context.asyncAssertSuccess(statusCodes -> context.assertEquals(new StatusCodes(200, 200, 200, 413, 200, 200, 200), statusCodes)));
	}

}
