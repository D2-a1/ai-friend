import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;
import com.fasterxml.jackson.databind.*;

/** Explicit loopback-only smoke. Synthetic identity, real socket/auth; no external model. */
class LocalAssistantSocketSmoke {
    static final String BASE="http://127.0.0.1:18080/api/v1";
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    static String token;
    static JsonNode request(String method,String path,Object body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(BASE+path)).timeout(Duration.ofSeconds(25))
            .header("Content-Type","application/json");
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(method.equals("PUT"))builder.header("Idempotency-Key",UUID.randomUUID().toString());
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        var response=HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        var result=JSON.readTree(response.body());
        if(response.statusCode()!=200)throw new IllegalStateException("HTTP="+response.statusCode()+" CODE="+result.path("code").asText());
        return result.path("data");
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !Set.of("SYNTHETIC_LOOPBACK_SMOKE","SYNTHETIC_GRAPH_LOOPBACK_SMOKE").contains(args[0]))throw new IllegalArgumentException("Explicit mode required");
        var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair=generator.generateKeyPair();var code="local_smoke_"+UUID.randomUUID()+"."+UUID.randomUUID();
        var signature=Signature.getInstance("SHA256withECDSA");signature.initSign(pair.getPrivate());
        signature.update("ai-friend-device-login-v1".getBytes(StandardCharsets.US_ASCII));signature.update((byte)0);
        signature.update(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        var login=request("POST","/auth/wechat/sessions",Map.of("code",code,"device",Map.of(
            "platform","ANDROID","osVersion","12","appVersion","0.0.1","deviceModel","Synthetic socket smoke",
            "publicKeySpkiBase64",Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
            "proofBase64",Base64.getEncoder().encodeToString(signature.sign()))));
        token=login.path("accessToken").asText();if(token.isBlank())throw new IllegalStateException("Missing token");
        request("PUT","/privacy/consents/BASIC_IDENTITY",Map.of("decision","GRANTED","policyVersion","1.0.0","confirmedAt",Instant.now().toString()));
        int failures=0;
        for(var question:args[0].equals("SYNTHETIC_GRAPH_LOOPBACK_SMOKE")?List.of("SYNTHETIC_EMPTY_GRAPH"):List.of("unmatched-local-smoke","绿灯按钮","SYNTHETIC_EMPTY_GRAPH")) {
            boolean graph=question.equals("SYNTHETIC_EMPTY_GRAPH");
            if(graph)request("PUT","/privacy/consents/CONTACT_GRAPH",Map.of("decision","GRANTED","policyVersion","contact-graph-v1","confirmedAt",Instant.now().toString()));
            var session=request("POST","/assistant/sessions",Map.of("purpose",graph?"CONTACT_GRAPH":"PUBLIC_KNOWLEDGE","clientRequestId",UUID.randomUUID().toString()));
            String id=session.path("sessionId").asText();if(id.isBlank())id=session.path("id").asText();
            long version=session.path("version").asLong();
            try {
                long start=System.nanoTime();
                String key=UUID.randomUUID().toString();
                var payload=graph ? Map.of("expectedVersion",version,"requestKey",key,"locale","zh-CN","appVersionCode",1,"graphQuery",Map.of("queryType","LIST_CONTACTS")) :
                    Map.of("expectedVersion",version,"requestKey",key,"locale","zh-CN","appVersionCode",1,"text",question);
                var answer=request("POST","/assistant/sessions/"+id+"/questions",payload);
                // PROCESSING is not a final answer. Poll only the original key, never regenerate.
                for(int poll=0;answer.path("status").asText().equals("PROCESSING") && poll<8;poll++) {
                    Thread.sleep(500);
                    answer=request("GET","/assistant/sessions/"+id+"/requests/"+key,null);
                }
                version=answer.path("version").asLong(version);
                System.out.println("CASE="+(graph?"EMPTY_GRAPH":question.startsWith("unmatched")?"NO_MATCH":"FIXTURE")+" STATUS="+answer.path("status").asText()+" MODE="+answer.path("answerMode").asText()+" REASON="+answer.path("reasonCode").asText()+" CITATIONS="+answer.path("citations").size()+" MS="+(System.nanoTime()-start)/1000000);
                boolean valid=graph ? answer.path("status").asText().equals("NO_EVIDENCE") && answer.path("candidates").isEmpty() : question.startsWith("unmatched") ? answer.path("status").asText().equals("NO_EVIDENCE") :
                    answer.path("status").asText().equals("EVIDENCE_ONLY") && answer.path("citations").size()==1 &&
                    answer.path("citations").get(0).path("text").asText().equals("绿灯按钮用于打开夜间模式。");
                if(!valid)failures++;
                var reread=request("GET","/assistant/sessions/"+id+"/requests/"+key,null);
                var replay=request("POST","/assistant/sessions/"+id+"/questions",payload);
                if(!answer.equals(reread) || !answer.equals(replay))failures++;
            } finally {
                try {request("DELETE","/assistant/sessions/"+id+"?expectedVersion="+version,null);}
                catch(Exception cleanupFailure) {failures++;System.out.println("SESSION_CLEANUP=FAILED");}
                finally {
                    if(graph) {
                        try {request("PUT","/privacy/consents/CONTACT_GRAPH",Map.of("decision","REVOKED","policyVersion","contact-graph-v1","confirmedAt",Instant.now().toString()));}
                        catch(Exception revokeFailure) {failures++;System.out.println("GRAPH_CONSENT_CLEANUP=FAILED");}
                    }
                }
            }
        }
        token=null;
        if(failures>0)throw new IllegalStateException("Smoke assertions failed: "+failures);
        System.out.println("LOOPBACK_ASSERTIONS=PASS");
    }
}
