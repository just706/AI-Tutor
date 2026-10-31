package com.aitutor.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.aitutor.exception.AiServiceException;
import org.springframework.stereotype.Component;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Component
public class PythonAgentClient {
    private final AgentProperties properties;
    private final ObjectMapper mapper;
    private final URI base;
    private final HttpClient client;
    public PythonAgentClient(AgentProperties properties,ObjectMapper mapper) {
        this.properties=properties;
        this.mapper=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        base=URI.create(properties.getBaseUrl().replaceAll("/+$",""));
        String host=base.getHost();
        boolean local=host!=null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
        if (host==null || !("https".equals(base.getScheme()) || ("http".equals(base.getScheme()) && local))
                || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null
                || properties.getTimeoutMs()<100 || properties.getTimeoutMs()>60000)
            throw new IllegalArgumentException("Invalid Python Agent URL or timeout");
        if (properties.isEnabled() && !PythonAgentGateway.validSecret(properties.getServiceToken()))
            throw new IllegalArgumentException("Configure a Python Agent service token of at least 24 ASCII characters");
        // Uvicorn仅支持HTTP/1.1；JDK默认的h2c升级会使请求体被错误处理。
        client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public JsonNode run(Map<String,Object> request) {
        if (!properties.isEnabled()) throw new AiServiceException("教材 Agent 未启用");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(properties.getTimeoutMs());
        try {
            // 演示模型不能误接入用户会话；健康检查不会触发模型调用。
            JsonNode health=exchange(HttpRequest.newBuilder(URI.create(base+"/health")).GET().build(),
                    Math.min(5000,properties.getTimeoutMs()));
            if (!"live".equals(health.path("mode").asText()) || !"1".equals(health.path("version").asText()))
                throw new AiServiceException("教材 Agent 未处于已配置的接入模式");
            byte[] body=mapper.writeValueAsBytes(request);
            if (body.length>65536) throw new AiServiceException("教材 Agent 请求超过容量限制");
            var outgoing=HttpRequest.newBuilder(URI.create(base+"/v1/runs"))
                    .header("Authorization","Bearer "+properties.getServiceToken()).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            return exchange(outgoing,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime())));
        } catch (AiServiceException ex) { throw ex; }
        catch (Exception ex) { throw new AiServiceException("教材 Agent 暂时不可用，请检查服务后重试"); }
    }

    private JsonNode exchange(HttpRequest request,long timeoutMs) {
        CompletableFuture<HttpResponse<byte[]>> future=client.sendAsync(request,ignored->new LimitedBody());
        try {
            var response=future.get(timeoutMs,TimeUnit.MILLISECONDS);
            if (response.statusCode()!=200) throw new AiServiceException("教材 Agent 请求失败（HTTP "+response.statusCode()+"）");
            JsonNode result=mapper.readTree(response.body());
            if (result==null || !result.isObject()) throw new AiServiceException("教材 Agent 返回格式无效");
            return result;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new AiServiceException("教材 Agent 请求已中断");
        } catch (TimeoutException ex) { throw new AiServiceException("教材 Agent 处理超时，请稍后检查结果"); }
        catch (AiServiceException ex) { throw ex; }
        catch (Exception ex) { throw new AiServiceException("教材 Agent 暂时不可用或返回格式无效"); }
        finally { if (!future.isDone()) future.cancel(true); }
    }

    // 限制实际接收的字节数，不能只相信 Content-Length 或等读取完后再检查。
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription=value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer:buffers) {
                if ((long)bytes.size()+buffer.remaining()>262144) {
                    subscription.cancel(); result.completeExceptionally(new IllegalArgumentException("Response too large")); return;
                }
                byte[] block=new byte[buffer.remaining()]; buffer.get(block); bytes.writeBytes(block);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
