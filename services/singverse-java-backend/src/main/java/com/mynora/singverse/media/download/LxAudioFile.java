package com.mynora.singverse.media.download;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Container and frame-boundary checks; no compute node or external decoder is required. */
final class LxAudioFile {
    private LxAudioFile() {}
    private static final int[] CRC16=new int[256];
    static {for(int i=0;i<256;i++){int c=i<<8;for(int bit=0;bit<8;bit++)c=(c&0x8000)!=0?(c<<1)^0x8005:c<<1;CRC16[i]=c&0xffff;}}
    static void validate(Path path,String extension)throws IOException {
        try(var f=new RandomAccessFile(path.toFile(),"r")) {
            switch(extension){case "wav"->wav(f);case "m4a"->mp4(f);case "ogg"->ogg(f);case "flac"->flac(f);case "mp3"->frames(f,false);case "aac"->frames(f,true);default->throw new IOException();}
        }
    }
    private static String text(RandomAccessFile f,int n)throws IOException{byte[] b=new byte[n];f.readFully(b);return new String(b,StandardCharsets.ISO_8859_1);}
    private static long u32le(RandomAccessFile f)throws IOException{return Integer.toUnsignedLong(Integer.reverseBytes(f.readInt()));}
    private static void require(boolean valid)throws IOException{if(!valid)throw new IOException("音频容器或帧不完整");}
    private static void wav(RandomAccessFile f)throws IOException {
        require(text(f,4).equals("RIFF"));long end=u32le(f)+8;require(text(f,4).equals("WAVE")&&end==f.length());boolean format=false,audio=false;
        while(f.getFilePointer()<end){require(end-f.getFilePointer()>=8);String type=text(f,4);long size=u32le(f),start=f.getFilePointer(),next=start+size+(size&1);require(next<=end);
            if(type.equals("fmt ")){require(size>=16);int encoding=Short.toUnsignedInt(Short.reverseBytes(f.readShort()));int channels=Short.toUnsignedInt(Short.reverseBytes(f.readShort()));long rate=u32le(f);require(encoding>0&&channels>0&&channels<=64&&rate>0&&rate<=768000);format=true;}
            if(type.equals("data")){require(size>0);audio=true;}f.seek(next);
        }require(format&&audio);
    }
    private static void mp4(RandomAccessFile f)throws IOException {
        boolean type=false,metadata=false,audio=false;long end=f.length();
        while(f.getFilePointer()<end){long start=f.getFilePointer();require(end-start>=8);long size=Integer.toUnsignedLong(f.readInt());String box=text(f,4);int header=8;if(size==1){size=f.readLong();header=16;}if(size==0)size=end-start;require(size>=header&&size<=end-start);
            if(box.equals("ftyp")){require(size>=16);type=true;}if(box.equals("moov")){require(size>header);metadata=true;}if(box.equals("mdat")&&size>header)audio=true;f.seek(start+size);
        }require(type&&metadata&&audio);
    }
    private static void ogg(RandomAccessFile f)throws IOException {
        boolean audio=false,eos=false;long end=f.length();
        while(f.getFilePointer()<end){require(end-f.getFilePointer()>=27&&text(f,4).equals("OggS")&&f.readUnsignedByte()==0);int flags=f.readUnsignedByte();f.skipBytes(20);int count=f.readUnsignedByte();require(count>0&&f.getFilePointer()+count<=end);int size=0;for(int i=0;i<count;i++)size+=f.readUnsignedByte();require(f.getFilePointer()+size<=end);audio|=size>0;eos=(flags&4)!=0;f.seek(f.getFilePointer()+size);}
        require(audio&&eos);
    }
    private static void flac(RandomAccessFile f)throws IOException {
        require(text(f,4).equals("fLaC"));boolean last=false,stream=false;
        for(int blocks=0;!last;blocks++){require(blocks<4096&&f.length()-f.getFilePointer()>=4);int header=f.readUnsignedByte();last=(header&128)!=0;int type=header&127;int size=(f.readUnsignedByte()<<16)|(f.readUnsignedByte()<<8)|f.readUnsignedByte();long next=f.getFilePointer()+size;require(next<=f.length());
            if(blocks==0){require(type==0&&size==34);f.skipBytes(10);long info=f.readLong();require((info>>>44)>0&&(info&0xfffffffffL)>0);stream=true;}f.seek(next);
        }
        require(stream&&f.length()-f.getFilePointer()>=8);long start=f.getFilePointer();require(f.readUnsignedByte()==255&&(f.readUnsignedByte()&0xfe)==0xf8);f.seek(start);
        int crc=0,n;byte[] buffer=new byte[65536];while((n=f.read(buffer))!=-1)for(int i=0;i<n;i++)crc=((crc<<8)^CRC16[((crc>>>8)^(buffer[i]&255))&255])&0xffff;require(crc==0);
    }
    private static void frames(RandomAccessFile f,boolean aac)throws IOException {
        long end=f.length();require(end>=7);String marker=text(f,3);f.seek(0);
        if(marker.equals("ID3")){require(end>=10);f.skipBytes(3);int version=f.readUnsignedByte();f.readUnsignedByte();int flags=f.readUnsignedByte();int size=0;for(int i=0;i<4;i++){int part=f.readUnsignedByte();require(part<128);size=(size<<7)|part;}long next=10L+size+(version==4&&(flags&16)!=0?10:0);require(next<end);f.seek(next);}
        int frames=0;
        while(f.getFilePointer()<end){long start=f.getFilePointer(),left=end-start;
            if(!aac&&left==128&&text(f,3).equals("TAG")){f.seek(end);break;}f.seek(start);
            if(!aac&&left>=32&&text(f,8).equals("APETAGEX")){f.skipBytes(4);long size=u32le(f);require(size>=32&&(size==left||size+32==left));f.seek(end);break;}f.seek(start);
            require(left>=7);int a=f.readUnsignedByte(),b=f.readUnsignedByte(),c=f.readUnsignedByte(),d=f.readUnsignedByte();require(a==255);
            int size;
            if(aac){require((b&0xf6)==0xf0&&((c>>2)&15)<13);int e=f.readUnsignedByte(),g=f.readUnsignedByte();f.readUnsignedByte();size=((d&3)<<11)|(e<<3)|(g>>5);require(size>=((b&1)==1?7:9));}
            else {int version=(b>>3)&3,layer=(b>>1)&3,rateIndex=(c>>2)&3,bitrateIndex=c>>4;require((b&0xe0)==0xe0&&version!=1&&layer!=0&&rateIndex<3&&bitrateIndex>0&&bitrateIndex<15);
                int[] rates={44100,48000,32000};int rate=rates[rateIndex]/(version==3?1:version==2?2:4),padding=(c>>1)&1;
                int[][] mpeg1={{0,32,40,48,56,64,80,96,112,128,160,192,224,256,320},{0,32,48,56,64,80,96,112,128,160,192,224,256,320,384},{0,32,64,96,128,160,192,224,256,288,320,352,384,416,448}};
                int[] low={0,8,16,24,32,40,48,56,64,80,96,112,128,144,160};int[] lowLayer1={0,32,48,56,64,80,96,112,128,144,160,176,192,224,256};
                int bitrate=version==3?mpeg1[layer-1][bitrateIndex]:(layer==3?lowLayer1:low)[bitrateIndex];size=layer==3?(12*bitrate*1000/rate+padding)*4:((layer==1&&version!=3?72:144)*bitrate*1000/rate+padding);
            }
            require(size>=7&&size<=left);f.seek(start+size);frames++;
        }require(frames>0);
    }
}
