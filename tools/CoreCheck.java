package com.aziz.stickeratolyesi;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class CoreCheck {
    private static int checks;
    private static void check(boolean value,String message) {
        checks++; if(!value) throw new AssertionError(message);
    }
    private static void rejected(byte[] bytes) throws Exception {
        boolean failed=false;
        try { Webp.inspect(bytes); } catch(IOException expected) { failed=true; }
        check(failed,"Malformed input accepted");
    }
    public static void main(String[] args) throws Exception {
        for(int n=0;n<=10000;n++) {
            List<Integer> sizes=PackPlanner.sizes(n); int sum=0;
            for(int size:sizes) { check(size>=1&&size<=30,"pack maximum"); if(n>=3) check(size>=3,"pack minimum"); sum+=size; }
            check(sum==n,"lost or duplicated stickers");
        }
        check(PackPlanner.sizes(31).equals(Arrays.asList(28,3)),"31 split");
        check(PackPlanner.sizes(32).equals(Arrays.asList(29,3)),"32 split");
        check(PackPlanner.sizes(2000).size()==67,"2000 count");
        Path root=Paths.get(args[0]);
        for(String kind:new String[]{"lossy","lossless"}) {
            Webp.Animation animation=new Webp.Animation();
            for(int n=0;n<3;n++) {
                byte[] still=Files.readAllBytes(root.resolve(kind+n+".webp"));
                Webp.Info i=Webp.inspect(still); check(Webp.stickerCompatible(still,i),"static compatibility");
                animation.add(still,120+n*30);
            }
            byte[] bytes=animation.finish(); Webp.Info i=Webp.inspect(bytes);
            check(i.frames.size()==3,"frame count"); check(i.duration==450,"duration");
            check(Webp.stickerCompatible(bytes,i),"animated compatibility");
            Files.write(root.resolve(kind+"-animated.webp"),bytes);
            for(int n=0;n<i.frames.size();n++) Files.write(root.resolve(kind+"-frame"+n+".webp"),Webp.standalone(i.frames.get(n)));
            rejected(Arrays.copyOf(bytes,bytes.length-1));
            byte[] bad=bytes.clone(); bad[4]=0; rejected(bad);
            bad=bytes.clone(); bad[16]=(byte)0xff; bad[17]=(byte)0xff; bad[18]=(byte)0xff; bad[19]=(byte)0x7f; rejected(bad);
            i.frames.get(0).duration=7; check(!Webp.stickerCompatible(bytes,i),"minimum duration");
        }
        rejected(new byte[0]); rejected(new byte[100]);
        boolean shortRejected=false; try { new Webp.Animation().finish(); } catch(IOException e) { shortRejected=true; }
        check(shortRejected,"empty animation");
        System.out.println("PASS: "+checks+" assertions (pack planning, RIFF, static/animated constraints)");
    }
}
