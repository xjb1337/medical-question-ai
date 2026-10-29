import com.xjb.medicalquestionai.*;
import java.lang.reflect.*;
import java.util.*;
import kotlin.coroutines.*;
import kotlinx.coroutines.*;
import kotlinx.coroutines.flow.*;
import sun.misc.Unsafe;

// Calls compiled production stop logic with isolated jobs; no UI/network/device claim.
public class NextCaptureRound7Probe {
  static Unsafe unsafe;
  static void set(Object vm,String key,Object value)throws Exception{
    Field f=MainViewModel.class.getDeclaredField(key);f.setAccessible(true);f.set(vm,value);
  }
  static MainViewModel vm(CompletableJob analysis,CompletableJob follow)throws Exception{
    MainViewModel vm=(MainViewModel)unsafe.allocateInstance(MainViewModel.class);
    set(vm,"_isAnalyzing",StateFlowKt.MutableStateFlow(false));
    set(vm,"_isCancelling",StateFlowKt.MutableStateFlow(false));
    Map<String,Job> jobs=new HashMap<>();Map<String,String> ids=new HashMap<>();
    if(follow!=null){jobs.put("q",follow);ids.put("q","follow-new");}
    set(vm,"followUpJobs",jobs);set(vm,"followUpRequestIds",ids);
    if(analysis!=null){set(vm,"analysisJob",analysis);set(vm,"activeQuestionId","q");set(vm,"activeRequestId","analysis-new");}
    return vm;
  }
  static StopOutcome stop(MainViewModel vm,String q,WorkIdentity identity){
    Object out=vm.stopQuestionWork(q,StopReason.PAGE_EXIT,identity,4000L,new Continuation<StopOutcome>(){
      public CoroutineContext getContext(){return EmptyCoroutineContext.INSTANCE;}
      public void resumeWith(Object out){throw new AssertionError("unexpected asynchronous continuation");}
    });
    return (StopOutcome)out;
  }
  static void check(String name,WorkIdentity identity,boolean withAnalysis,boolean withFollow,boolean cancelAnalysis,boolean cancelFollow,boolean ignored)throws Exception{
    CompletableJob a=withAnalysis?JobKt.Job((Job)null):null,b=withFollow?JobKt.Job((Job)null):null;
    StopOutcome out=stop(vm(a,b),"q",identity);
    if((a!=null&&a.isCancelled())!=cancelAnalysis||(b!=null&&b.isCancelled())!=cancelFollow||out.getIgnored()!=ignored||!out.getStopped())throw new AssertionError(name+": "+out);
    System.out.println("PASS "+name+" analysisCancelled="+(a!=null&&a.isCancelled())+" followUpCancelled="+(b!=null&&b.isCancelled())+" ignored="+out.getIgnored());
  }
  public static void main(String[] args)throws Exception{
    Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(Unsafe)f.get(null);
    check("empty snapshot protects new analysis",new WorkIdentity(null,null),true,false,false,false,true);
    check("empty snapshot protects new follow-up",new WorkIdentity(null,null),false,true,false,false,true);
    check("old analysis id protects new follow-up",new WorkIdentity("analysis-old",null),false,true,false,false,true);
    check("old analysis id protects new analysis",new WorkIdentity("analysis-old",null),true,false,false,false,true);
    check("old follow-up id protects new follow-up",new WorkIdentity(null,"follow-old"),false,true,false,false,true);
    check("current analysis stops",new WorkIdentity("analysis-new",null),true,false,true,false,false);
    check("current follow-up stops",new WorkIdentity(null,"follow-new"),false,true,false,true,false);
    check("independent task ownership",new WorkIdentity("analysis-old","follow-new"),true,true,false,true,false);
    check("no work is idempotent",new WorkIdentity(null,null),false,false,false,false,false);
    CompletableJob a=JobKt.Job((Job)null),b=JobKt.Job((Job)null);
    StopOutcome out=stop(vm(a,b),"other-question",new WorkIdentity("analysis-new","follow-new"));
    if(a.isCancelled()||b.isCancelled()||!out.getStopped())throw new AssertionError("other question");
    System.out.println("PASS other question remains active");
  }
}
