package ph.ligtask;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import com.fasterxml.jackson.databind.*;
import java.util.*;
@Controller
public class WebController {
 private final ProvisionalJsonAdapter adapter; private final DatasetStore store;
 public WebController(ProvisionalJsonAdapter adapter,DatasetStore store){this.adapter=adapter;this.store=store;}
 record Pending(byte[] bytes,String filename,String token){}
 @GetMapping("/") String home(Model m){m.addAttribute("datasets",store.all());return "home";}
 @PostMapping("/preview") String preview(@RequestParam MultipartFile file,HttpSession session,Model m) throws Exception {
  session.removeAttribute("pending"); byte[] bytes=file.getBytes(); DatasetDocument d=adapter.parse(bytes);
  if(d.valid()&&store.exists(d,bytes))d.errors().add("Dataset name/version or exact source file already imported. Choose a new version only for changed data.");
  String token=UUID.randomUUID().toString();
  if(d.valid())session.setAttribute("pending",new Pending(bytes,Objects.toString(file.getOriginalFilename(),"upload.json"),token));
  m.addAttribute("document",d);m.addAttribute("token",token);m.addAttribute("coverage",Coverage.of(d.records()));return "preview";
 }
 @PostMapping("/import") String commit(@RequestParam String token,HttpSession session,RedirectAttributes flash){
  Pending p=(Pending)session.getAttribute("pending");
  if(p==null||!p.token().equals(token)){flash.addFlashAttribute("message","Preview expired or replaced. Upload and preview again.");return "redirect:/";}
  DatasetDocument d=adapter.parse(p.bytes());
  try {UUID id=store.save(d,p.bytes(),p.filename());session.removeAttribute("pending");return "redirect:/evaluation/"+id+"/coverage";}
  catch(DataIntegrityViolationException e){session.removeAttribute("pending");flash.addFlashAttribute("message","Import rejected: dataset/version, source file, or record already exists. Nothing was imported.");return "redirect:/";}
 }
 @GetMapping("/datasets/{id}") String dataset(@PathVariable UUID id) {
  try {store.dataset(id);}catch(EmptyResultDataAccessException e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);}
  return "redirect:/evaluation/"+id+"/coverage";
 }
 @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class) String tooLarge(RedirectAttributes f){f.addFlashAttribute("message","Upload exceeds the 10 MB limit. Select a smaller JSON file.");return "redirect:/";}
}
