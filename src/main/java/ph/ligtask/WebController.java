package ph.ligtask;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.util.*;

@Controller
public class WebController {
 private final DatasetImporter adapter; private final DatasetStore store;
 public WebController(DatasetImporter adapter,DatasetStore store){this.adapter=adapter;this.store=store;}
 record Pending(byte[] bytes,String filename,String token){}
 record UploadResult(boolean valid,List<String> errors,String previewUrl){}
 @GetMapping("/") String home(Model m){m.addAttribute("datasets",store.all());m.addAttribute("archivedDatasets",store.archived());return "home";}

 private DatasetDocument prepare(MultipartFile file,HttpSession session) throws Exception {
  session.removeAttribute("pending");
  byte[] bytes=file.getBytes(); DatasetDocument d=adapter.parse(bytes);
  if(d.valid())store.existing(d,bytes).ifPresent(existing -> d.errors().add(existing.get("archived_at") != null
   ? "This dataset was already imported and removed from the active list. Restore it under Removed datasets to reuse its saved records."
   : "Dataset name/version or exact source file already imported. Choose a new version only for changed data."));
  if(d.valid())session.setAttribute("pending",new Pending(bytes,Objects.toString(file.getOriginalFilename(),"upload.json"),UUID.randomUUID().toString()));
  return d;
 }
 private String renderPreview(DatasetDocument d,Pending pending,Model m) {
  m.addAttribute("previewRecords",d.records().subList(0,Math.min(24,d.records().size())));
  m.addAttribute("document",d);m.addAttribute("token",pending.token());m.addAttribute("coverage",Coverage.of(d.records()));return "preview";
 }
 @PostMapping(value="/preview",produces=MediaType.TEXT_HTML_VALUE,headers="X-Requested-With!=fetch")
 String preview(@RequestParam MultipartFile file,HttpSession session,Model m) throws Exception {
  DatasetDocument d=prepare(file,session);
  if(!d.valid()){m.addAttribute("uploadErrors",d.errors());return home(m);}
  return renderPreview(d,(Pending)session.getAttribute("pending"),m);
 }
 @PostMapping(value="/preview",produces=MediaType.APPLICATION_JSON_VALUE,headers="X-Requested-With=fetch")
 @ResponseBody
 ResponseEntity<UploadResult> validateUpload(@RequestParam MultipartFile file,HttpSession session) throws Exception {
  DatasetDocument d=prepare(file,session);
  return ResponseEntity.status(d.valid()?HttpStatus.OK:HttpStatus.UNPROCESSABLE_ENTITY)
   .body(new UploadResult(d.valid(),d.errors(),d.valid()?"/preview":null));
 }
 @GetMapping("/preview") String pendingPreview(HttpSession session,Model m,RedirectAttributes flash) {
  Pending pending=(Pending)session.getAttribute("pending");
  if(pending==null){flash.addFlashAttribute("message","Preview expired or replaced. Upload and preview again.");return "redirect:/";}
  DatasetDocument d=adapter.parse(pending.bytes());
  if(!d.valid()||store.exists(d,pending.bytes())) {
   session.removeAttribute("pending");
   if(d.valid())d.errors().add("This dataset has already been imported.");
   m.addAttribute("uploadErrors",d.errors());return home(m);
  }
  return renderPreview(d,pending,m);
 }
 @PostMapping("/import") String commit(@RequestParam String token,HttpSession session,RedirectAttributes flash){
  Pending p=(Pending)session.getAttribute("pending");
  if(p==null||!p.token().equals(token)){flash.addFlashAttribute("message","Preview expired or replaced. Upload and preview again.");return "redirect:/";}
  DatasetDocument d=adapter.parse(p.bytes());
  if(!d.valid()){session.removeAttribute("pending");flash.addFlashAttribute("message","Import rejected: dataset structure validation failed. Upload and preview again.");return "redirect:/";}
  try {UUID id=store.save(d,p.bytes(),p.filename());session.removeAttribute("pending");return "redirect:/evaluation/"+id+"/coverage";}
  catch(DataIntegrityViolationException e){session.removeAttribute("pending");flash.addFlashAttribute("message","Import rejected: dataset/version, source file, or record already exists. Nothing was imported.");return "redirect:/";}
 }
 @PostMapping("/preview/clear")
 @ResponseStatus(HttpStatus.NO_CONTENT)
 @ResponseBody
 void clearPreview(HttpSession session){session.removeAttribute("pending");}

 @PostMapping("/datasets/{id}/archive")
 String archive(@PathVariable UUID id,RedirectAttributes flash){
  if(!store.archive(id))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
  flash.addFlashAttribute("notice","Dataset removed from the active list. Its stored records are retained.");
  return "redirect:/";
 }
 @PostMapping("/datasets/{id}/restore")
 String restore(@PathVariable UUID id,RedirectAttributes flash){
  if(!store.restore(id))throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND);
  flash.addFlashAttribute("notice","Dataset restored. Existing records are ready to reuse.");
  return "redirect:/";
 }
 @GetMapping("/datasets/{id}") String dataset(@PathVariable UUID id) {
  try {store.dataset(id);}catch(EmptyResultDataAccessException e){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND);}
  return "redirect:/evaluation/"+id+"/coverage";
 }
 @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
 Object tooLarge(HttpServletRequest request,HttpSession session,RedirectAttributes flash){
  session.removeAttribute("pending");
  String message="Upload exceeds the 10 MB limit. Select a smaller JSON file.";
  if(Objects.toString(request.getHeader("Accept"),"").contains(MediaType.APPLICATION_JSON_VALUE))
   return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new UploadResult(false,List.of(message),null));
  flash.addFlashAttribute("message",message);return "redirect:/";
 }
}
