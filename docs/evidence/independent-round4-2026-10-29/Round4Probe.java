package com.xjb.medicalquestionai.data;
import java.util.*;
import java.lang.reflect.*;
public class Round4Probe {
 public static void main(String[] args) throws Exception {
  String base="{\"status\":\"answered\",\"question_type\":\"single\",\"answer\":[\"A、B\"],\"warnings\":[]}";
  for(String raw:List.of(base,base.replace("A、B","A").replace("single","banana"),base.replace("A、B","A").replace("\"warnings\":[]","\"warnings\":[],\"option_analysis\":[{\"option\":\"B\",\"reason\":\"fixture\"}]"))) {
   AnalysisPreview p=new PreviewJsonParser().feed(raw,QuestionType.SINGLE);
   AnalysisResult r=AnalysisResultParser.INSTANCE.parse(raw,new QuestionDraft(QuestionType.SINGLE,"fixture",Collections.emptyList(),true));
   System.out.println("preview="+(p==null?"null":p.toResult().getStatus()+" answer="+p.toResult().getAnswer()+" complete="+p.isComplete()+" issue="+p.getSemanticIssue())+" final="+r.getStatus());
  }
  Method parse=DeepSeekClient.class.getDeclaredMethod("parseStreamEvent",String.class); parse.setAccessible(true);
  DeepSeekClient c=new DeepSeekClient(); int fake=0;
  for(int i=0;i<100;i++) for(Object event:(List<?>)parse.invoke(c,"{\"choices\":[{\"delta\":{\"content\":null,\"reasoning_content\":null},\"finish_reason\":null}]}"))
   if(event instanceof ChatStreamEvent.ContentDelta || event instanceof ChatStreamEvent.ReasoningDelta) fake++;
  System.out.println("100_null_frames_fake_deltas="+fake);
  List<FollowUp> history=List.of(new FollowUp("u","q","user","fixture",1L,new Usage(0,0,0),false,0L),new FollowUp("a","q","assistant","nullnullnull"+"fixture answer",2L,new Usage(0,0,0),false,0L));
  for(Method m:QuestionAnalyzer.Companion.getClass().getDeclaredMethods()) if(m.getName().startsWith("trimFollowUpHistory") && m.getParameterCount()==2) {
   m.setAccessible(true); List<?> kept=(List<?>)m.invoke(QuestionAnalyzer.Companion,history,2000);
   System.out.println("polluted_history_kept="+kept.size());
  }
 }
}

