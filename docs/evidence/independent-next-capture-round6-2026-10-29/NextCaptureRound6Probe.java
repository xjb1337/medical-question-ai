import com.xjb.medicalquestionai.*;
import java.lang.reflect.*;
import java.util.*;
import kotlin.coroutines.*;
import kotlinx.coroutines.*;
import kotlinx.coroutines.flow.*;
import sun.misc.Unsafe;
import androidx.lifecycle.*;
import androidx.compose.runtime.*;

// Exercises the compiled production stop function with isolated in-memory jobs.
// Bypasses Android initialization; does not simulate UI lifecycle or network transport.
public class NextCaptureRound6Probe {
  static Unsafe unsafe;
  static void set(Object vm, String name, Object value) throws Exception {
    Field f=MainViewModel.class.getDeclaredField(name); f.setAccessible(true); f.set(vm,value);
  }
  static MainViewModel vm() throws Exception {
    MainViewModel vm=(MainViewModel)unsafe.allocateInstance(MainViewModel.class);
    set(vm,"followUpJobs",new HashMap<String,Job>());
    set(vm,"_isAnalyzing",StateFlowKt.MutableStateFlow(false));
    set(vm,"_isCancelling",StateFlowKt.MutableStateFlow(false));
    return vm;
  }
  static Object stop(MainViewModel vm,String expected) {
    return vm.stopQuestionWork("q",StopReason.PAGE_EXIT,expected,4000L,new Continuation<StopOutcome>() {
      public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}
      public void resumeWith(Object value){System.out.println("resumed="+value);}
    });
  }
  public static void main(String[] args) throws Exception {
    Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);
    MainViewModel a=vm(); CompletableJob newFollowUp=JobKt.Job((Job)null);
    MainViewModel camera=vm();set(camera,"activeCameraTokens",new HashSet<String>());
    MutableState<String> token=SnapshotStateKt.mutableStateOf(null,SnapshotStateKt.structuralEqualityPolicy());
    Lifecycle lifecycle=new Lifecycle(){
      public void addObserver(LifecycleObserver o){}
      public void removeObserver(LifecycleObserver o){}
      public Lifecycle.State getCurrentState(){return Lifecycle.State.RESUMED;}
    };
    LifecycleOwner owner=()->lifecycle;
    LifecycleEventObserver observer=(o,e)->{};
    Class<?> disposeClass=Class.forName("com.xjb.medicalquestionai.ui.screens.CameraScreenKt$CameraScreen$2$invoke$$inlined$onDispose$1");
    Object oldEffectDispose=disposeClass.getConstructors()[0].newInstance(owner,observer,null,camera,token);
    String current=camera.beginCameraCapture();token.setValue(current);
    Method active=MainViewModel.class.getDeclaredMethod("isCameraCaptureActive",String.class);active.setAccessible(true);
    System.out.println("cameraTokenActiveBeforeOldEffectDispose="+active.invoke(camera,current));
    ((DisposableEffectResult)oldEffectDispose).dispose();
    System.out.println("cameraTokenActiveAfterOldEffectDispose="+active.invoke(camera,current));
    if((boolean)active.invoke(camera,current))throw new AssertionError("expected production disposer to invalidate current token");
    Map<String,Job> jobs=new HashMap<>();jobs.put("q",newFollowUp);set(a,"followUpJobs",jobs);
    Object out1=stop(a,"old-analysis-request");
    System.out.println("staleNonNullId_cancelledNewFollowUp="+newFollowUp.isCancelled()+" outcome="+out1);
    MainViewModel b=vm();CompletableJob newAnalysis=JobKt.Job((Job)null);
    set(b,"analysisJob",newAnalysis);set(b,"activeQuestionId","q");set(b,"activeRequestId","new-analysis-request");
    Object out2=stop(b,null);
    System.out.println("staleNullId_cancelledNewAnalysis="+newAnalysis.isCancelled()+" outcome="+out2);
    MainViewModel c=vm();CompletableJob protectedAnalysis=JobKt.Job((Job)null);
    set(c,"analysisJob",protectedAnalysis);set(c,"activeQuestionId","q");set(c,"activeRequestId","new-analysis-request");
    Object out3=stop(c,"old-analysis-request");
    System.out.println("staleNonNullId_cancelledNewAnalysis="+protectedAnalysis.isCancelled()+" outcome="+out3);
    if(!newFollowUp.isCancelled()||!newAnalysis.isCancelled()||protectedAnalysis.isCancelled())throw new AssertionError("unexpected probe outcome");
  }
}
