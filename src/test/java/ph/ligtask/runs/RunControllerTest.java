package ph.ligtask.runs;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.MediaType;
import org.springframework.dao.EmptyResultDataAccessException;
import ph.ligtask.DatasetStore;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@WebMvcTest(RunController.class) @WithMockUser
class RunControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean RunCoordinator coordinator;@MockitoBean RunStore store;@MockitoBean DatasetStore datasets;
    final UUID dataset=UUID.randomUUID(),run=UUID.randomUUID();
    String api(){return "/evaluation/"+dataset+"/runs/api";}
    @BeforeEach void setup(){when(store.run(dataset,run)).thenReturn(Map.of("id",run,"status","QUEUED"));when(store.counts(run)).thenReturn(List.of());}
    @Test void startRequiresCsrf() throws Exception {
        mvc.perform(post(api()).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());verifyNoInteractions(coordinator);
    }
    @Test void acknowledgedPostCreatesRunButGetDoesNotExecute() throws Exception {
        when(coordinator.start(eq(dataset),any())).thenReturn(run);
        mvc.perform(post(api()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"clientToken\":\""+UUID.randomUUID()+"\",\"models\":[\"gpt-4o\"],\"promptLimit\":1,\"maxTokens\":512,\"acknowledgeCosts\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run.id").value(run.toString()));
        clearInvocations(coordinator);
        mvc.perform(get(api())).andExpect(status().isOk());verifyNoInteractions(coordinator);
    }
    @Test void crossDatasetRunCannotBeReadOrCancelledOrExported() throws Exception {
        when(store.run(dataset,run)).thenThrow(new EmptyResultDataAccessException(1));
        mvc.perform(get(api()+"/"+run)).andExpect(status().isNotFound());
        mvc.perform(post(api()+"/"+run+"/cancel").with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get(api()+"/"+run+"/export")).andExpect(status().isNotFound());verify(store,never()).cancel(any());
    }
    @Test void cancelRequiresCsrfAndKeepsTheRun() throws Exception {
        mvc.perform(post(api()+"/"+run+"/cancel")).andExpect(status().isForbidden());
        mvc.perform(post(api()+"/"+run+"/cancel").with(csrf())).andExpect(status().isOk());verify(store).cancel(run);
    }
    @Test void responsePaginationIsBounded() throws Exception {
        mvc.perform(get(api()+"/"+run+"/responses").param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get(api()+"/"+run+"/responses")).andExpect(status().isOk()).andExpect(jsonPath("$.pageSize").value(50));
    }
    @Test void exportIncludesSavedResponsesAsAttachment() throws Exception {
        when(store.export(run)).thenReturn(List.of(Map.of("status","INVALID_RESPONSE","response","raw response")));
        mvc.perform(get(api()+"/"+run+"/export")).andExpect(status().isOk()).andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("attachment")))
            .andExpect(jsonPath("$.responses[0].status").value("INVALID_RESPONSE"));
    }
    @Test void retryAndDeleteRequireCsrfAndAreScopedToTheDataset() throws Exception {
        mvc.perform(post(api()+"/"+run+"/retry").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete(api()+"/"+run)).andExpect(status().isForbidden());
        verify(coordinator,never()).retry(any(),any(),any());verify(store,never()).delete(any(),any());
        when(store.run(dataset,run)).thenThrow(new EmptyResultDataAccessException(1));
        mvc.perform(get(api()+"/"+run+"/retry-plan")).andExpect(status().isNotFound());
        mvc.perform(post(api()+"/"+run+"/retry").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(delete(api()+"/"+run).with(csrf())).andExpect(status().isNotFound());
        verify(coordinator,never()).retry(any(),any(),any());verify(store,never()).delete(any(),any());
    }
    @Test void retryPreviewIsReadOnlyAndConfirmedRetryAndDeleteUseTheirServices() throws Exception {
        when(coordinator.retryPlan(dataset,run)).thenReturn(Map.of("requests",3,"preserved",2,"uncertain",1));
        mvc.perform(get(api()+"/"+run+"/retry-plan")).andExpect(status().isOk()).andExpect(jsonPath("$.requests").value(3));
        verify(coordinator,never()).retry(any(),any(),any());
        when(coordinator.retry(eq(dataset),eq(run),any())).thenReturn(run);
        mvc.perform(post(api()+"/"+run+"/retry").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"clientToken\":\""+UUID.randomUUID()+"\",\"acknowledgeCosts\":true,\"acknowledgeUncertain\":true}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.run.id").value(run.toString()));
        when(store.delete(dataset,run)).thenReturn(Map.of("deletedRun",run,"sharedResponsesRetained",1));
        mvc.perform(delete(api()+"/"+run).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.sharedResponsesRetained").value(1));
        verify(store).delete(dataset,run);
    }
}
