#!/usr/bin/env python3
"""Exercise the production downloader with a tiny local model and an in-memory URL handler. No network."""
from pathlib import Path
import tempfile, subprocess
base=Path(__file__).resolve().parents[1]/'app/src/main/java/org/thunderdog/challegram'
src=(base/'stt/SttManager.java').read_text(); a=src.index('  private void downloadFiles'); b=src.index('  private static String sha256',a)
code='''import java.io.*;import java.net.*;import java.nio.file.*;import java.util.*;
class ModelProbe {
 static class SttModel {String id="test"; FileSpec[] files={new FileSpec()};long totalSize(){return 8;}static class FileSpec {String url="https://fixture.invalid/test",name="model",sha256=null;long size=8;}}
 static class DownloadTask {SttModel model=new SttModel();boolean cancelled;}
 static class SystemClock {static long uptimeMillis(){return 0;}} static class UI {static void post(Runnable r){}}
 interface Listener {void onModelDownloadProgress(SttModel m);} List<Listener> modelListeners=new ArrayList<>();Map<String,DownloadTask> downloads=new HashMap<>();Map<String,Float> downloadProgress=new HashMap<>();
 static long SPACE_RESERVE=0; static class NoSpaceException extends IOException {NoSpaceException(long n){}} static File dir; File getModelsDir(){return dir;} File getModelDir(SttModel m){return dir;} String sha256(File f){return "";}
 static byte[] body;static int status;static String range;static boolean closed;
 static class Conn extends HttpURLConnection { Conn(URL u){super(u);} public int getResponseCode(){return status;}public void disconnect(){closed=true;} public void connect(){}public boolean usingProxy(){return false;} public InputStream getInputStream(){return new ByteArrayInputStream(body);}public String getHeaderField(String n){return "Content-Range".equalsIgnoreCase(n)?range:null;} }
''' + src[a:b] + '''
 static void run(int n,int http,String cr,boolean pass,int previous) throws Exception {
  Path file=dir.toPath().resolve("model"),part=dir.toPath().resolve("model.part");Files.deleteIfExists(file);Files.write(part,new byte[previous]);body=new byte[n];range=cr;status=http;closed=false;boolean ok=true;
  try {new ModelProbe().downloadFiles(new DownloadTask());} catch(IOException e){ok=false;}
  if(ok!=pass || (Files.exists(part) && Files.size(part)>8) || (pass && Files.size(file)!=8) || !closed)throw new AssertionError("http="+http+" n="+n+" previous="+previous+" ok="+ok+" connectionClosed="+closed+" part="+(Files.exists(part)?Files.size(part):-1)); }
 public static void main(String[] args)throws Exception {dir=new File(args[0]);URL.setURLStreamHandlerFactory(protocol-> new URLStreamHandler(){protected URLConnection openConnection(URL u){return new Conn(u);}});
  run(9,200,null,false,0);run(8,200,null,true,0);run(5,206,"bytes 3-7/8",true,3);run(5,206,"bytes 0-4/8",false,3);run(8,200,null,true,3);run(0,404,null,false,0);run(2,200,null,false,0);System.out.println("PASS bounded download, resume, rejected range, restart, short and error responses");}}
'''
with tempfile.TemporaryDirectory() as d:
 f=Path(d)/'ModelProbe.java';f.write_text(code);subprocess.run(['javac',str(f)],check=True);subprocess.run(['java','-cp',d,'ModelProbe',d],check=True)
