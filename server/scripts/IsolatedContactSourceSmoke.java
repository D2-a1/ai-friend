import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import com.aifriend.knowledge.application.*;
import com.aifriend.knowledge.domain.*;
import com.aifriend.knowledge.infrastructure.*;
import com.aifriend.assistant.application.KnowledgeAccessPolicy;
import com.aifriend.consent.application.ConsentGrantQueryPort;
import com.aifriend.consent.domain.ConsentType;
import com.aifriend.contact.application.*;
import com.aifriend.contact.infrastructure.ContactGraphSourceAdapter;
import com.aifriend.shared.security.*;
import com.aifriend.shared.error.BusinessException;

/** Real contact SQL/AES/projection/query. Synthetic enrollment and JDBC consent bridge,
 * not OAuth, signed acoustic package validation, or full Spring HTTP wiring. */
class IsolatedContactSourceSmoke extends IsolatedGraphSmoke {
    static UUID binding(JdbcTemplate jdbc,UUID owner) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO contact_binding(id,owner_user_id,contact_subject_hash,status,created_by,consented_at,verified_at,created_at,updated_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),?,'ACTIVE',UUID_TO_BIN(?),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id.toString(),owner.toString(),random(),owner.toString());return id;
    }
    static UUID alias(JdbcTemplate jdbc,SensitiveDataProtector protector,UUID owner,UUID binding,String text) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO contact_alias(id,binding_id,owner_user_id,display_text_cipher,dialect_code,dialect_package_version,template_model_version,threshold_version,template_cipher,template_digest,status,create_idempotency_key_hash,create_request_hash,created_at,updated_at) VALUES(UUID_TO_BIN(?),UUID_TO_BIN(?),UUID_TO_BIN(?),?,'synthetic','v1','v1','v1',?,?,'ACTIVE',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id.toString(),binding.toString(),owner.toString(),protector.encrypt(text),random(),random(),random(),random());return id;
    }
    static GraphQueryPort.Query list(UUID owner) {return new GraphQueryPort.Query(owner,GraphQueryType.LIST_CONTACTS,Optional.empty(),Optional.empty());}
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !args[3].equals("SYNTHETIC_CONTACT_SOURCE") || !args[0].matches("[a-zA-Z0-9.-]+") || !args[1].matches("[a-zA-Z0-9_]+") || !args[2].matches("[a-zA-Z0-9_]+") || System.console()==null)throw new IllegalArgumentException("Explicit isolated mode required");
        char[] secret=System.console().readPassword("Test database password: ");if(secret==null || secret.length==0)throw new IllegalArgumentException("Missing secret");
        var config=new HikariConfig();config.setJdbcUrl("jdbc:mysql://"+args[0]+":3306/"+args[1]+"?sslMode=REQUIRED&connectTimeout=5000&socketTimeout=15000&connectionTimeZone=UTC");
        config.setUsername(args[2]);config.setPassword(new String(secret));Arrays.fill(secret,'\0');config.setMaximumPoolSize(3);config.setMinimumIdle(1);
        try(var data=new HikariDataSource(config)) {
            var jdbc=new JdbcTemplate(data);jdbc.setQueryTimeout(5);
            check(args[1].equals(jdbc.queryForObject("SELECT DATABASE()",String.class)),"DATABASE_MATCH");
            Flyway.configure().dataSource(data).locations("filesystem:src/main/resources/db/migration").cleanDisabled(true).load().validate();
            var tx=new DataSourceTransactionManager(data);
            var access=new KnowledgeAccessPolicy(new ConsentGrantQueryPort(){
                public boolean isGranted(UUID owner,ConsentType type){return isGrantedForPolicy(owner,type,KnowledgeAccessPolicy.GRAPH_POLICY);}
                public boolean isGrantedForPolicy(UUID owner,ConsentType type,String policy){var found=jdbc.query("SELECT decision,policy_version FROM consent_record WHERE user_id=UUID_TO_BIN(?) AND type=? ORDER BY sequence_no DESC LIMIT 1",(r,n)->r.getString(1).equals("GRANTED") && r.getString(2).equals(policy),owner.toString(),type.name());return !found.isEmpty() && found.get(0);}
            });
            var protector=new SensitiveDataProtector(new SecurityKeyMaterial(new SecretKeySpec(random(),"HmacSHA256"),new SecretKeySpec(random(),"AES"),new SecretKeySpec(random(),"HmacSHA256")));
            var acoustic=(AcousticTemplatePort)java.lang.reflect.Proxy.newProxyInstance(AcousticTemplatePort.class.getClassLoader(),new Class[]{AcousticTemplatePort.class},(p,m,a)->{
                if(m.getName().equals("isCompatible"))return Arrays.equals(a,new Object[]{"synthetic","v1","v1","v1"});throw new UnsupportedOperationException("NO_AUDIO_IN_GRAPH_TEST");});
            var source=new ContactGraphSourceAdapter(data,tx,access,acoustic,protector);
            var store=new JdbcKnowledgeGraphAdapter(data,tx,access);var cleanup=new JdbcGraphOwnerCleanupAdapter(data,tx);
            var query=new ContactGraphQueryService(source,store,access);
            UUID owner=UUID.randomUUID(),other=UUID.randomUUID();System.out.println("SYNTHETIC_OWNERS="+owner+","+other);account(jdbc,owner);account(jdbc,other);
            try {
                consent(jdbc,owner,"GRANTED");consent(jdbc,other,"GRANTED");
                UUID first=binding(jdbc,owner),second=binding(jdbc,owner),foreign=binding(jdbc,other);
                UUID firstAlias=alias(jdbc,protector,owner,first,"合成同名称呼");alias(jdbc,protector,owner,second,"合成同名称呼");alias(jdbc,protector,other,foreign,"合成外部称呼");
                var all=query.query(list(owner));check(all.candidates().size()==2,"REAL_CONTACTS_PROJECTED");
                check(all.candidates().stream().noneMatch(c->c.contactId().equals(foreign)),"FOREIGN_CONTACT_EXCLUDED");
                check(all.candidates().stream().allMatch(c->c.aliases().equals(List.of("合成同名称呼"))),"REAL_AES_ALIAS_DECRYPTION");
                var match=query.query(new GraphQueryPort.Query(owner,GraphQueryType.FIND_CONTACT_BY_ALIAS,Optional.empty(),Optional.of("合成同名称呼")));
                check(match.ambiguous() && match.candidates().size()==2,"DUPLICATE_ALIAS_STAYS_AMBIGUOUS");
                check(query.query(new GraphQueryPort.Query(owner,GraphQueryType.LIST_ALIASES,Optional.of(foreign),Optional.empty())).candidates().isEmpty(),"FOREIGN_ID_QUERY_EMPTY");
                var before=source.snapshot(owner);
                jdbc.update("UPDATE contact_alias SET status='DELETED',display_text_cipher=NULL,template_cipher=NULL,template_digest=NULL,deleted_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=UUID_TO_BIN(?) AND owner_user_id=UUID_TO_BIN(?)",firstAlias.toString(),owner.toString());
                boolean stale=false;try{source.displayCurrent(owner,before.sourceDigest(),List.of(first));}catch(GraphSourceException e){stale=e.kind()==GraphSourceException.Kind.SOURCE_CHANGED;}
                check(stale,"STALE_SOURCE_DIGEST_REJECTED");
                var refreshed=query.query(list(owner));check(!refreshed.sourceDigest().equals(before.sourceDigest()),"SOURCE_CHANGE_REBUILDS_PROJECTION");
                check(refreshed.candidates().stream().filter(c->c.contactId().equals(first)).allMatch(c->c.aliases().isEmpty()),"DELETED_ALIAS_NOT_RETURNED");
                jdbc.update("UPDATE contact_binding SET status='REVOKED',revoked_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=UUID_TO_BIN(?) AND owner_user_id=UUID_TO_BIN(?)",first.toString(),owner.toString());
                check(query.query(list(owner)).candidates().size()==1,"REVOKED_CONTACT_NOT_RETURNED");
                consent(jdbc,owner,"REVOKED");boolean denied=false;try{query.query(list(owner));}catch(BusinessException e){denied=true;}check(denied,"CONSENT_REVOKE_DENIES_QUERY");
                check(query.query(list(other)).candidates().size()==1,"OTHER_OWNER_STILL_AVAILABLE");
            } finally {
                for(UUID id:List.of(owner,other)) {
                    consent(jdbc,id,"REVOKED");cleanup.purgeOwner(id);
                    jdbc.update("UPDATE contact_alias SET status='DELETED',display_text_cipher=NULL,phonetic_hint_cipher=NULL,template_cipher=NULL,template_digest=NULL,deleted_at=UTC_TIMESTAMP(3),version=version+1 WHERE owner_user_id=UUID_TO_BIN(?)",id.toString());
                    jdbc.update("UPDATE contact_binding SET status='REVOKED',revoked_at=UTC_TIMESTAMP(3),version=version+1 WHERE owner_user_id=UUID_TO_BIN(?)",id.toString());
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM contact_alias WHERE owner_user_id=UUID_TO_BIN(?) AND (display_text_cipher IS NOT NULL OR template_cipher IS NOT NULL)",Integer.class,id.toString())==0,"SYNTHETIC_ALIAS_MATERIAL_CLEARED");
                }
            }
            System.out.println("RESULT=PASS; synthetic contact tombstones retained, graph purged, no model or device action");
        }
    }
}
