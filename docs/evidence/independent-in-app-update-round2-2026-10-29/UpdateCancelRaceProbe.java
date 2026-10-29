import android.app.Application;
import android.content.*;
import androidx.lifecycle.*;
import com.xjb.medicalquestionai.data.update.*;
import kotlinx.coroutines.*;
import kotlinx.coroutines.flow.*;
import kotlinx.coroutines.test.TestDispatchers;
import kotlin.coroutines.*;
import okhttp3.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import sun.misc.Unsafe;
public class UpdateCancelRaceProbe {
 static Object get(Object x,String n)throws Exception{Field f=x.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(x);}
 static void set(Object x,String n,Object v)throws Exception{Field f=x.getClass().getDeclaredField(n);f.setAccessible(true);f.set(x,v);}
 public static class App extends Application {
  File dir;
  public File getCacheDir(){return dir;}
  public SharedPreferences getSharedPreferences(String n,int m){return (SharedPreferences)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{SharedPreferences.class},(o,method,args)->method.getName().equals("getLong")?0L:null);}
 }
 public static void main(String[] a)throws Exception{
  Field uf=Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);Unsafe u=(Unsafe)uf.get(null);
  App app=(App)u.allocateInstance(App.class);app.dir=new File("E:/souti/acceptance-artifacts/update-race-cache");app.dir.mkdirs();
  TestDispatchers.setMain(Dispatchers.INSTANCE,new CoroutineDispatcher(){public void dispatch(CoroutineContext c,Runnable r){r.run();}});
  CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1),releaseFirst=new CountDownLatch(1),releaseSecond=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();
  OkHttpClient client=new OkHttpClient.Builder().addInterceptor(chain->{int n=requests.incrementAndGet();try{if(n==1){first.countDown();releaseFirst.await();}else{second.countDown();releaseSecond.await();}}catch(InterruptedException e){throw new IOException(e);}return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("test failure").body(ResponseBody.create("",MediaType.get("text/plain"))).build();}).build();
  UpdateViewModel vm=new UpdateViewModel(app);set(vm,"repository",new UpdateRepository(client));
  UpdateManifest manifest=new UpdateManifest(1,"com.xjb.medicalquestionai",4,"1.2.1",26,"medical-question-ai.apk",100,"a".repeat(64),"b".repeat(64),"c".repeat(40),"2026-10-29","test");
  UpdateUiState state=new UpdateUiState(UpdateStatus.AVAILABLE,3,"1.2.0",new UpdateRelease(manifest,"https://github.com/xjb1337/medical-question-ai/releases/download/v4/medical-question-ai.apk","v4"),null,0,100,null,null,false,false,false,null);
  ((MutableStateFlow<UpdateUiState>)get(vm,"_state")).setValue(state);
  vm.download();if(!first.await(5,TimeUnit.SECONDS))throw new AssertionError("first not started");Job old=(Job)get(vm,"downloadJob");
  vm.cancelDownload();vm.download();if(!second.await(5,TimeUnit.SECONDS))throw new AssertionError("second not started");Job current=(Job)get(vm,"downloadJob");
  releaseFirst.countDown();long end=System.currentTimeMillis()+5000;while(!old.isCompleted()&&System.currentTimeMillis()<end)Thread.sleep(10);
  System.out.println("newJobStillActive="+current.isActive());
  System.out.println("downloadJobReferenceClearedByOldFinally="+(get(vm,"downloadJob")==null));
  System.out.println("activeCallReferenceClearedByOldFinally="+(get(vm,"activeDownloadCall")==null));
  System.out.println("downloadingFlagWhileNewJobActive="+get(vm,"downloading"));
  System.out.println("uiStatusWhileNewJobActive="+((MutableStateFlow<UpdateUiState>)get(vm,"_state")).getValue().getStatus());
  boolean broken=current.isActive()&&get(vm,"downloadJob")==null;releaseSecond.countDown();current.cancel(null);TestDispatchers.resetMain(Dispatchers.INSTANCE);
  if(!broken)throw new AssertionError("race not observed");
 }
}

