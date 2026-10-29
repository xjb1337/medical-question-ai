package com.xjb.medicalquestionai.data;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowCollector;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.Protocol;
import okhttp3.MediaType;
import org.json.JSONObject;
import sun.misc.Unsafe;

/** Independent synthetic probes. The interceptor returns fixtures before any network I/O. */
public class DeviceFeedbackRound2Probe {
    static final String GOOD = "{\"status\":\"answered\",\"question_type\":\"single\",\"answer\":[\"A\"],\"warnings\":[]}";
    static void parserProbe(String name, String raw, QuestionType selected) {
        AnalysisPreview preview = new PreviewJsonParser().feed(raw, selected);
        QuestionDraft draft = new QuestionDraft(selected, "Synthetic fixture", Collections.emptyList(), true);
        AnalysisResult result = AnalysisResultParser.INSTANCE.parse(raw, draft);
        System.out.println(name + ": preview=" + (preview == null ? "BLOCKED" : preview.getStatus() + " " + preview.getAnswer())
            + " final=" + result.getStatus() + " " + result.getAnswer());
    }
    static ImageStore unusedImageStore() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (ImageStore) ((Unsafe)field.get(null)).allocateInstance(ImageStore.class);
    }
    static void nonStreamProbe(String finish) throws Exception {
        JSONObject choice = new JSONObject().put("message", new JSONObject().put("content", GOOD));
        if (finish != null) choice.put("finish_reason", finish);
        String fixture = new JSONObject().put("model", "my-model").put("choices", new org.json.JSONArray().put(choice)).toString();
        OkHttpClient fakeHttp = new OkHttpClient.Builder().addInterceptor(chain -> {
            okio.Buffer capture = new okio.Buffer();
            chain.request().body().writeTo(capture);
            JSONObject payload = new JSONObject(capture.readUtf8());
            System.out.println("unknown_request: stream=" + payload.optBoolean("stream") + " thinking=" + payload.has("thinking") + " jsonMode=" + payload.has("response_format"));
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(MediaType.get("application/json"), fixture)).build();
        }).build();
        DeepSeekClient client = new DeepSeekClient();
        Field httpField = DeepSeekClient.class.getDeclaredField("client");
        httpField.setAccessible(true);
        httpField.set(client, fakeHttp);
        QuestionAnalyzer analyzer = new QuestionAnalyzer(client, unusedImageStore());
        QuestionDraft draft = new QuestionDraft(QuestionType.SINGLE, "Synthetic fixture", Collections.emptyList(), true);
        Flow<AnalysisEvent> flow = analyzer.analyzeStream(draft, "synthetic-not-a-key", "my-model", "https://synthetic.invalid/v1", AnswerMode.FAST, false, false);
        List<String> result = new ArrayList<>();
        FlowCollector<AnalysisEvent> collector = (event, continuation) -> {
            if (event instanceof AnalysisEvent.Completed) {
                AnalysisResult parsed = ((AnalysisEvent.Completed)event).getResult();
                result.add("COMPLETED status=" + parsed.getStatus() + " recordedFinish=" + parsed.getMetrics().getFinishReason());
            } else if (event instanceof AnalysisEvent.Failed) result.add("FAILED " + ((AnalysisEvent.Failed)event).getMessage());
            return Unit.INSTANCE;
        };
        BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) -> flow.collect(collector, continuation));
        System.out.println("nonstream_finish_" + finish + ": " + result);
        fakeHttp.dispatcher().executorService().shutdown();
        fakeHttp.connectionPool().evictAll();
    }
    public static void main(String[] args) throws Exception {
        parserProbe("single_two_answers", GOOD.replace("[\"A\"]", "[\"A\",\"B\"]"), QuestionType.SINGLE);
        parserProbe("invalid_status", GOOD.replace("answered", "banana"), QuestionType.SINGLE);
        parserProbe("warnings_wrong_type", GOOD.replace("\"warnings\":[]", "\"warnings\":\"confirm\""), QuestionType.SINGLE);
        parserProbe("explicit_type_conflict", GOOD.replace("single", "multiple").replace("[\"A\"]", "[\"A\",\"B\"]"), QuestionType.SINGLE);
        parserProbe("invalid_type_manual_single", GOOD.replace("single", "banana"), QuestionType.SINGLE);
        parserProbe("missing_type_manual_single", GOOD.replace("\"question_type\":\"single\",", ""), QuestionType.SINGLE);
        parserProbe("option_analysis_mismatch", GOOD.replace("\"warnings\":[]", "\"warnings\":[],\"option_analysis\":[{\"option\":\"B\",\"reason\":\"fixture\"}]"), QuestionType.SINGLE);
        parserProbe("choice_delimiter_multiple_letters", GOOD.replace("[\"A\"]", "[\"A、B\"]"), QuestionType.SINGLE);
        parserProbe("short_without_answer_text", GOOD.replace("single", "short"), QuestionType.SHORT);
        parserProbe("good", GOOD, QuestionType.SINGLE);
        for(String finish : new String[]{"stop", "length", "content_filter", null}) nonStreamProbe(finish);
    }
}
