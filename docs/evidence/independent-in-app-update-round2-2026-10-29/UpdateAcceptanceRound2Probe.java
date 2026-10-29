import com.xjb.medicalquestionai.data.update.*;
import kotlin.coroutines.*;
import okhttp3.*;
import okio.*;
import org.json.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class UpdateAcceptanceRound2Probe {
 static String prefix="https://github.com/xjb1337/medical-question-ai/releases/download/";
 static String release(String updateTag,String apkTag){return new JSONObject().put("tag_name","new").put("draft",false).put("prerelease",false).put("assets",new JSONArray().put(new JSONObject().put("name","update.json").put("content_type","application/json").put("browser_download_url",prefix+updateTag+"/update.json")).put(new JSONObject().put("name","medical-question-ai.apk").put("content_type","application/vnd.android.package-archive").put("browser_download_url",prefix+apkTag+"/medical-question-ai.apk"))).toString();}
 static String manifest(){return new JSONObject().put("schemaVersion",1).put("applicationId","com.xjb.medicalquestionai").put("versionCode",4).put("versionName","1.2.1").put("minSdk",26).put("apkAssetName","medical-question-ai.apk").put("apkSizeBytes",100).put("apkSha256","a".repeat(64)).put("signingCertSha256","b".repeat(64)).put("commitSha","c".repeat(40)).put("publishedAt","2026-10-29").put("releaseNotes","test").toString();}
 static Object check(UpdateRepository repo)throws Exception{
   CompletableFuture<Object> future=new CompletableFuture<>();
   Object out=repo.checkForUpdate(3,35,new Continuation<UpdateCheckResult>(){
     public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}
     public void resumeWith(Object value){future.complete(value);}
   });
   return out==kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED()?future.get(10,TimeUnit.SECONDS):out;
 }
 static ResponseBody counting(String text,AtomicLong consumed){
   Buffer data=new Buffer().writeUtf8(text);
   BufferedSource source=Okio.buffer(new ForwardingSource(data){public long read(Buffer sink,long count)throws java.io.IOException{long n=super.read(sink,count);if(n>0)consumed.addAndGet(n);return n;}});
   return new ResponseBody(){public MediaType contentType(){return MediaType.get("application/json");}public long contentLength(){return -1;}public BufferedSource source(){return source;}};
 }
 public static void main(String[] args)throws Exception{
   AtomicLong consumed=new AtomicLong();
   OkHttpClient cross=new OkHttpClient.Builder().addInterceptor(chain->new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(counting(chain.request().url().host().equals("api.github.com")?release("old","new"):manifest(),consumed)).build()).build();
   Object mixed=check(new UpdateRepository(cross));
   System.out.println("crossReleaseAssetsAccepted="+(mixed instanceof UpdateCheckResult.Available));
   AtomicLong metadataRead=new AtomicLong();
   String huge=manifest()+" ".repeat(2*1024*1024);
   OkHttpClient large=new OkHttpClient.Builder().addInterceptor(chain->new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(counting(chain.request().url().host().equals("api.github.com")?release("new","new"):huge,chain.request().url().host().equals("api.github.com")?new AtomicLong():metadataRead)).build()).build();
   Object result=check(new UpdateRepository(large));
   System.out.println("manifestBytesConsumedBeforeReject="+metadataRead.get()+" configuredLimit="+UpdateManifestParser.MAX_METADATA_BYTES+" result="+result);
   if((mixed instanceof UpdateCheckResult.Available)||metadataRead.get()>UpdateManifestParser.MAX_METADATA_BYTES+8192||metadataRead.get()==0)throw new AssertionError("unexpected probe result");
 }
}

