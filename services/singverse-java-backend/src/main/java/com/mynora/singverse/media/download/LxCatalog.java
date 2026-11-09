package com.mynora.singverse.media.download;

import static com.mynora.singverse.media.download.LxTypes.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import okhttp3.HttpUrl;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Public catalog adapters; independent of metadata scraping accounts/settings. */
@Component
public class LxCatalog {
    public static final int PAGE_SIZE=20;
    private final LxNetwork network;
    private final ObjectMapper json;
    public LxCatalog(LxNetwork network,ObjectMapper json){this.network=network;this.json=json;}
    public record Page(String platform,String kind,String name,String coverUrl,List<Track> tracks,int page,
            long total,boolean hasMore,String warning) {}
    public record Reference(String platform,String kind,String id) {}
    public Page search(String platform,String query,int page) {
        if(!PLATFORMS.containsKey(platform)||query==null||query.isBlank()||query.length()>100||page<1||page>100)throw bad("请输入 1–100 个字的歌名或歌手，页码最多 100");
        JsonNode root,rows;long total;
        switch(platform) {
            case "wy" -> {root=postForm("https://music.163.com/api/search/get/web",platform,"s",query,"type","1","limit",PAGE_SIZE,"offset",(page-1)*PAGE_SIZE);rows=root.path("result").path("songs");total=root.path("result").path("songCount").asLong();}
            case "tx" -> {root=get(url("https://c.y.qq.com/soso/fcgi-bin/client_search_cp","w",query,"p",page,"n",PAGE_SIZE,"format","json"),platform).path("data").path("song");if(root.has("curpage")&&root.path("curpage").asInt()!=page)throw unavailable("平台未返回新的搜索页，请稍后再试");rows=root.path("list");total=root.path("totalnum").asLong();}
            case "kg" -> {root=get(url("https://songsearch.kugou.com/song_search_v2","keyword",query,"page",page,"pagesize",PAGE_SIZE,"platform","WebFilter","format","json"),platform).path("data");rows=root.path("lists");var expanded=json.createArrayNode();for(JsonNode row:array(rows)){expanded.add(row);for(var version:array(row.path("Grp")))expanded.add(version);}rows=expanded;total=root.path("total").asLong();}
            case "kw" -> {root=get(url("https://search.kuwo.cn/r.s","all",query,"pn",page-1,"rn",PAGE_SIZE,"client","kt","ft","music","rformat","json","encoding","utf8"),platform);rows=root.path("abslist");total=root.path("TOTAL").asLong();}
            case "mg" -> {root=get(url("https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do","text",query,"pageNo",page,"pageSize",20,"searchSwitch","{\"song\":1}"),platform);JsonNode data=root.path("songResultData");if(data.isMissingNode())data=root.path("data").path("songResultData");rows=first(data,"result","resultList","songList");var flat=json.createArrayNode();for(var row:array(rows)){if(row.isArray())row.forEach(flat::add);else flat.add(row);}rows=flat;total=data.path("totalCount").asLong();}
            default -> throw bad("不支持的平台");
        }
        return page(platform,"search",query,"",rows,page,total,page*PAGE_SIZE<total,"");
    }
    public Page preview(String input,int page) {
        if(page<1||page>500)throw bad("每次最多读取 10000 首歌曲，请拆分后导入");
        Reference ref=reference(input);return collection(ref,page);
    }
    public Reference reference(String input) {
        if(input==null||input.length()>4096)throw bad("请粘贴歌单或专辑的分享链接");
        Matcher matcher=Pattern.compile("https?://[^\\s<>“”\"']+",Pattern.CASE_INSENSITIVE).matcher(input);
        if(!matcher.find())throw bad("未识别到分享链接，请从音乐平台复制歌单或专辑链接");
        String address=matcher.group().replaceAll("[。，、；！？）)\\]】]+$","");
        String platform=platform(address);Reference parsed=parseReference(address,platform);
        if(parsed!=null)return parsed;
        String cleanAddress=address.split("#",2)[0];LxNetwork.checked(cleanAddress,false);
        var response=network.request(cleanAddress,"GET",Map.of("User-Agent","Mozilla/5.0","Referer",cleanAddress),null,1024*1024,false,"lx-share-"+UUID.randomUUID());
        String finalPlatform=platform(response.url());
        if(!platform.equals(finalPlatform))throw bad("分享链接跳转到了其他平台，请复制原始歌单或专辑链接");
        parsed=parseReference(response.url(),platform);if(parsed!=null)return parsed;
        // Some official short links embed the target instead of issuing an HTTP redirect.
        String body=new String(response.bytes(),StandardCharsets.UTF_8).replace("\\/","/").replace("&amp;","&");
        Matcher links=Pattern.compile("https?://[^\\s<>\"']{1,2048}").matcher(body);
        while(links.find()){try{String link=links.group();if(platform(link).equals(platform)){parsed=parseReference(link,platform);if(parsed!=null)return parsed;}}catch(Exception ignored){}}
        if(platform.equals("kg")){Matcher collection=Pattern.compile("(?:global_collection_id|global_specialid|gcid)[\"']?\\s*[:=]\\s*[\"'](gcid_[A-Za-z0-9]+|collection_[A-Za-z0-9_]+)").matcher(body);if(collection.find())return new Reference("kg","playlist",collection.group(1));}
        throw bad("未识别到歌单或专辑，请检查链接是否公开，或复制完整分享链接");
    }
    static String platform(String address) {
        URI uri;try{uri=URI.create(address);}catch(Exception e){throw bad("分享链接无效");}
        String host=uri.getHost();if(host==null||uri.getUserInfo()!=null||!Set.of("http","https").contains(uri.getScheme())||(uri.getPort()!=-1&&uri.getPort()!=(uri.getScheme().equals("https")?443:80)))throw bad("请使用音乐平台的公开分享链接");
        var domains=Map.of("wy",List.of("music.163.com","163cn.tv"),"tx",List.of("y.qq.com","qqmusic.qq.com","qm.qq.com"),"kg",List.of("kugou.com"),"kw",List.of("kuwo.cn"),"mg",List.of("migu.cn"));
        for(var entry:domains.entrySet())for(String domain:entry.getValue())if(host.equals(domain)||host.endsWith("."+domain))return entry.getKey();
        throw bad("目前支持网易云、QQ、酷狗、酷我和咪咕的歌单或专辑链接");
    }
    static Reference parseReference(String address,String platform) {
        URI uri=URI.create(address);String full=uri.getPath()+(uri.getFragment()==null?"":uri.getFragment());
        String lower=full.toLowerCase(Locale.ROOT);String kind=lower.contains("album")?"album":lower.matches(".*(playlist|songlist|playsquare|special|diss|listdetail|list_details).*" )?"playlist":"";
        Map<String,String> query=new LinkedHashMap<>();
        for(String part:List.of(uri.getRawQuery()==null?"":uri.getRawQuery(),uri.getRawFragment()!=null&&uri.getRawFragment().contains("?")?uri.getRawFragment().split("\\?",2)[1]:"")) {
            for(String pair:part.split("&")){String[] split=pair.split("=",2);if(split.length==2)query.put(URLDecoder.decode(split[0],StandardCharsets.UTF_8),URLDecoder.decode(split[1],StandardCharsets.UTF_8));}
        }
        if(kind.isEmpty())kind=query.containsKey("global_collection_id")||query.containsKey("gcid")||query.containsKey("disstid")?"playlist":query.containsKey("albumId")||query.containsKey("albummid")?"album":"";
        if(kind.isEmpty())return null;
        List<String> keys=kind.equals("album")?List.of("albumId","albumid","albummid","id"):List.of("global_collection_id","gcid","specialid","disstid","playlistId","pid","id");
        String id="";for(String key:keys)if(query.containsKey(key)){id=query.get(key);break;}
        if(id.isEmpty()) {Matcher m=Pattern.compile("(?:album(?:_?detail)?|playlist(?:_?detail)?|playsquare|songlist|special/single|playlist/index)/(?:detail/|info/)?([A-Za-z0-9_]+)(?:\\.html|/|$)",Pattern.CASE_INSENSITIVE).matcher(full);if(m.find())id=m.group(1);}
        if(id.isEmpty()||id.length()>100||!id.matches(platform.equals("kg")?"(?:[0-9]+|gcid_[A-Za-z0-9]+|collection_[A-Za-z0-9_]+)":platform.equals("tx")?"[A-Za-z0-9]+":"[0-9]+"))return null;
        return new Reference(platform,kind,id);
    }
    Page collection(Reference ref,int page) {
        String p=ref.platform(),id=ref.id();boolean album=ref.kind().equals("album");
        JsonNode root,rows;String name="",cover="",warning="";long total;boolean sliced=false;
        switch(p) {
            case "wy" -> {
                root=get(album?url("https://music.163.com/api/album/"+id):url("https://music.163.com/api/v6/playlist/detail","id",id,"n",100000,"s",0),p);
                JsonNode info=root.path(album?"album":"playlist");name=text(info,"name");cover=text(info,album?"picUrl":"coverImgUrl");
                if(album){rows=root.path("songs");if(!rows.isArray())rows=info.path("songs");total=info.path("size").asLong(rows.size());sliced=true;}
                else {
                    JsonNode ids=info.path("trackIds");total=info.path("trackCount").asLong(ids.size());
                    if(ids.isArray()) {
                        var selected=json.createArrayNode();int start=(page-1)*PAGE_SIZE;
                        for(int i=start;i<Math.min(start+PAGE_SIZE,ids.size());i++)selected.addObject().set("id",ids.get(i).path("id"));
                        if(selected.isEmpty())rows=json.createArrayNode();else rows=get(url("https://music.163.com/api/v3/song/detail","c",selected.toString()),p).path("songs");
                        if(total>ids.size())warning="平台只公开了部分曲目，受限歌曲需要在原平台查看权限";
                    }else {rows=info.path("tracks");sliced=true;if(total>rows.size())warning="平台只返回部分歌曲，部分曲目可能需要登录";}
                }
            }
            case "tx" -> {
                if(album) {
                    String key=id.matches("[0-9]+")?"albumid":"albummid";
                    root=get(url("https://c.y.qq.com/v8/fcg-bin/fcg_v8_album_info_cp.fcg",key,id,"format","json"),p).path("data");
                    rows=root.path("list");name=text(root,"name");String mid=text(root,"mid");cover=mid.isEmpty()?"":"https://y.gtimg.cn/music/photo_new/T002R300x300M000"+mid+".jpg";total=root.path("total").asLong(rows.size());sliced=true;
                }else {
                    root=get(url("https://c.y.qq.com/v8/fcg-bin/fcg_v8_playlist_cp.fcg","id",id,"newsong",1,"format","json"),p).path("cdlist");
                    if(!root.isArray()||root.isEmpty())throw unavailable("该歌单不可读取，请检查是否为公开歌单");root=root.get(0);
                    rows=root.path("songlist");name=text(root,"dissname");cover=text(root,"logo");total=root.path("total_song_num").asLong(root.path("songnum").asLong(rows.size()));sliced=true;
                }
            }
            case "kg" -> {
                if(album) {
                    JsonNode info=get(url("http://mobilecdn.kugou.com/api/v3/album/info","albumid",id),p).path("data");name=text(info,"albumname");cover=text(info,"imgurl").replace("{size}","400");
                    root=get(url("http://mobilecdn.kugou.com/api/v3/album/song","albumid",id,"page",page,"pagesize",PAGE_SIZE),p).path("data");
                    rows=root.path("info");total=root.path("total").asLong(info.path("songcount").asLong());
                    for(var row:array(rows))if(row.isObject()){var song=(ObjectNode)row;if(text(row,"AlbumName","album_name").isEmpty())song.put("album_name",name);if(text(row,"AlbumID","album_id").isEmpty())song.put("album_id",id);if(text(row,"imgurl").isEmpty())song.put("imgurl",cover);}
                }else {
                    if(!id.matches("[0-9]+"))return kugouCollection(id,page);
                    JsonNode info=get(url("http://mobilecdnbj.kugou.com/api/v5/special/info","specialid",id),p).path("data");name=text(info,"specialname");cover=text(info,"imgurl").replace("{size}","400");
                    root=get(url("http://mobilecdnbj.kugou.com/api/v3/special/song","specialid",id,"page",page,"pagesize",PAGE_SIZE),p).path("data");rows=root.path("info");total=root.path("total").asLong(info.path("songcount").asLong());
                }
            }
            case "kw" -> {
                if(album) {
                    root=get(url("https://search.kuwo.cn/r.s","stype","albuminfo","albumid",id,"pn",page-1,"rn",PAGE_SIZE,"alflac",1,"show_copyright_off",1,"rformat","json","encoding","utf8"),p);
                    rows=first(root,"musiclist","songlist");name=text(root,"name","album");cover=text(root,"pic","img");total=first(root,"total","musicnum","totalnum","songnum").asLong();
                }else {
                    root=get(url("http://nplserver.kuwo.cn/pl.svc","op","getlistinfo","pid",id,"pn",page-1,"rn",PAGE_SIZE,"encode","utf8","keyset","pl2012","identity","kuwo","pcmp4",1,"vipver","MUSIC_9.0.5.0_W1","newver",1),p);
                    rows=root.path("musiclist");name=text(root,"title");cover=text(root,"pic");total=root.path("total").asLong();
                }
            }
            case "mg" -> {
                if(album) {
                    JsonNode info=get(url("https://app.c.nf.migu.cn/MIGUM3.0/resource/album/v2.0","albumId",id),p).path("data");
                    name=text(info,"title","albumName","name");cover=text(info.path("imgItems").path(0),"img","imgUrl");
                    root=get(url("https://app.c.nf.migu.cn/MIGUM3.0/resource/album/song/v2.0","albumId",id,"pageNo",page,"pageSize",PAGE_SIZE),p).path("data");
                    rows=root.path("songList");total=root.path("totalCount").asLong(info.path("totalCount").asLong(rows.size()));
                }else {
                    JsonNode info=get(url("https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0","playlistId",id),p).path("data");name=text(info,"title","playlistName","name");cover=text(info,"image","img","imageUrl");
                    root=get(url("https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0","playlistId",id,"pageNo",page,"pageSize",PAGE_SIZE),p).path("data");rows=root.path("songList");total=root.path("totalCount").asLong();
                }
            }
            default -> throw bad("不支持的平台");
        }
        if(!rows.isArray())throw unavailable("平台未返回歌曲列表，目录可能受限，请检查公开权限后重试");
        long readable=sliced?rows.size():total;
        if(sliced){if(total>rows.size())warning="平台只返回 "+rows.size()+" / "+total+" 首，部分歌曲需要在原平台查看权限";rows=slice(rows,page);}
        boolean more=page*PAGE_SIZE<readable&&rows.size()>0;
        if(!sliced&&rows.isEmpty()&&(long)(page-1)*PAGE_SIZE<total)warning="平台提前停止返回歌曲，已读取部分目录；请检查公开权限或稍后重试";
        if(page>=500&&more){more=false;warning="已达到单次读取 10000 首上限，请拆分歌单后继续";}
        return page(p,ref.kind(),name.isBlank()?(album?"专辑":"歌单")+" "+id:name,cover,rows,page,total,more,warning);
    }
    private Page kugouCollection(String id,int page) {
        var params=new TreeMap<String,String>();params.putAll(Map.of("appid","1058","specialid","0","global_specialid",id,"srcappid","2919","clientver","20000","clienttime","1586163263991","mid","1586163263991","uuid","1586163263991","dfid","-"));
        if(id.startsWith("gcid_")) {
            String body=encodeJson(args("ret_info",1,"data",List.of(args("id",id,"id_type",2))));
            String decodedUrl=signed("https://t.kugou.com/v1/songlist/batch_decode",Map.of("dfid","-","appid","1005","mid","0","clientver","20109","clienttime","640612895","uuid","-"),body);
            JsonNode decoded=send(decodedUrl,"kg","POST",Map.of("Content-Type","application/json"),body.getBytes(StandardCharsets.UTF_8)).path("data").path("list").path(0);
            id=decoded.path("global_collection_id").asText();if(!id.matches("collection_[A-Za-z0-9_]{1,100}"))throw unavailable("酷狗未返回公开歌单编号，请重新复制分享链接");params.put("global_specialid",id);
        }
        JsonNode info=get(signed("https://mobiles.kugou.com/api/v5/special/info_v2",params),"kg").path("data");
        params.put("page",String.valueOf(page));params.put("pagesize",String.valueOf(PAGE_SIZE));
        params.put("plat","0");params.put("version","8000");
        JsonNode root=get(signed("https://mobiles.kugou.com/api/v5/special/song_v2",params),"kg").path("data");
        long total=root.path("total").asLong(info.path("songcount").asLong());
        return page("kg","playlist",text(info,"specialname"),text(info,"imgurl").replace("{size}","400"),root.path("info"),page,total,page*PAGE_SIZE<total,"");
    }
    private String signed(String base,Map<String,String> parameters) {return signed(base,parameters,null);}
    private String signed(String base,Map<String,String> parameters,String body) {
        String salt=body==null?"NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt":"OIlwieks28dk2k092lksi2UIkp";var text=new StringBuilder(salt);new TreeMap<>(parameters).forEach((k,v)->text.append(k).append('=').append(v));if(body!=null)text.append(body);text.append(salt);
        try{String signature=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(text.toString().getBytes(StandardCharsets.UTF_8)));var b=Objects.requireNonNull(HttpUrl.parse(base)).newBuilder();parameters.forEach(b::addQueryParameter);return b.addQueryParameter("signature",signature).build().toString();}catch(Exception e){throw unavailable("歌单链接处理失败");}
    }
    public Track detail(Track track) {
        String p=track.platform(),id=track.remoteId();JsonNode rows;
        switch(p){
            case "wy" -> rows=get(url("https://music.163.com/api/v3/song/detail","c","[{\"id\":"+id+"}]"),p).path("songs");
            case "tx" -> rows=get(url("https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg","songmid",id,"format","json"),p).path("data");
            case "kw" -> rows=first(get(url("https://search.kuwo.cn/r.s","rid","MUSIC_"+id,"ft","music","client","kt","rformat","json","encoding","utf8"),p),"musiclist","abslist");
            case "kg" -> {var node=get(url("https://www.kugou.com/yy/index.php","r","play/getdata","hash",id),p).path("data");rows=json.createArrayNode().add(node);}
            case "mg" -> rows=get(url("https://app.u.nf.migu.cn/MIGUM3.0/resource/song/by-songids/v2.0","songId",id),p).path("data");
            default -> throw bad("不支持的平台");
        }
        if(!rows.isArray()||rows.isEmpty())throw unavailable("平台未返回歌曲详情");Track detailed=mapTrack(p,rows.get(0));if(!detailed.remoteId().equals(id))throw unavailable("平台返回的歌曲不匹配，请重新搜索");return detailed;
    }
    Page page(String platform,String kind,String name,String cover,JsonNode rows,int page,long total,boolean more,String warning) {
        if(!rows.isArray()&&total>0)throw unavailable("平台响应发生变化，请稍后重试");
        var tracks=new LinkedHashMap<String,Track>();int skipped=0;
        for(JsonNode row:array(rows)) {try{Track track=mapTrack(platform,row);tracks.putIfAbsent(track.id(),track);}catch(IllegalArgumentException error){skipped++;}}
        if(skipped>0)warning=(warning.isBlank()?"":warning+"；")+skipped+" 首缺少必要的歌曲信息，已略过";
        if(rows.size()>0&&tracks.isEmpty())throw unavailable("平台没有返回可解析的歌曲信息，请稍后再试");
        return new Page(platform,kind,cleanText(name),safeCover(cover),List.copyOf(tracks.values()),page,Math.max(total,tracks.size()),more,warning);
    }
    public Track mapTrack(String p,JsonNode row) {
        String id,title,album,cover,albumId;JsonNode singers;Long duration;ObjectNode meta=json.createObjectNode();var qualitys=json.createArrayNode();
        switch(p) {
            case "wy" -> {id=text(row,"id");title=text(row,"name");singers=first(row,"artists","ar");JsonNode al=first(row,"album","al");album=text(al,"name");albumId=text(al,"id");cover=text(al,"picUrl","blurPicUrl");duration=millis(first(row,"dt","duration"),false);meta.set("songId",row.path("id"));}
            case "tx" -> {id=text(row,"songmid","mid");title=text(row,"songname","title","name");singers=row.path("singer");JsonNode al=row.path("album");album=text(row,"albumname");if(album.isEmpty())album=text(al,"name");albumId=text(row,"albummid");if(albumId.isEmpty())albumId=text(al,"mid");cover=albumId.isEmpty()?"":"https://y.gtimg.cn/music/photo_new/T002R300x300M000"+albumId+".jpg";duration=millis(row.path("interval"),true);meta.set("songId",first(row,"songid","id"));meta.put("albumMid",albumId);meta.put("strMediaMid",text(row,"strMediaMid","media_mid").isEmpty()?text(row.path("file"),"media_mid"):text(row,"strMediaMid","media_mid"));JsonNode file=row.has("file")?row.path("file"):row;quality(qualitys,"128k",first(file,"size128","size_128mp3"),null);quality(qualitys,"320k",first(file,"size320","size_320mp3"),null);quality(qualitys,"flac",first(file,"sizeflac","size_flac"),null);}
            case "kg" -> {id=text(row,"FileHash","hash").toUpperCase(Locale.ROOT);if(!id.matches("[A-F0-9]{32}"))throw new IllegalArgumentException();String filename=text(row,"FileName","filename");title=text(row,"SongName","songname","audio_name");String artist=text(row,"SingerName","singername","author_name");if(title.isEmpty()){int split=filename.indexOf(" - ");title=split<0?filename:filename.substring(split+3);if(artist.isEmpty()&&split>=0)artist=filename.substring(0,split);}singers=json.valueToTree(artist);album=text(row,"AlbumName","album_name");albumId=text(row,"AlbumID","album_id");cover=text(row,"imgurl").replace("{size}","400");duration=millis(first(row,"Duration","duration","timelength"),!row.has("timelength"));meta.put("hash",id);quality(qualitys,"128k",first(row,"FileSize","size"),id);quality(qualitys,"320k",first(row,"HQFileSize","320filesize"),text(row,"HQFileHash","hash_320","320hash"));quality(qualitys,"flac",first(row,"SQFileSize","sqfilesize"),text(row,"SQFileHash","hash_flac","sqhash"));}
            case "kw" -> {id=text(row,"MUSICRID","musicrid","rid","id").replaceFirst("^MUSIC_","");title=text(row,"SONGNAME","name","songname");singers=first(row,"ARTIST","artist","singer");album=text(row,"ALBUM","album");albumId=text(row,"ALBUMID","albumid","albumId");cover=text(row,"albumpic","pic","img");duration=millis(first(row,"DURATION","duration"),true);meta.put("songId",id);String formats=text(row,"FORMATS","formats","KFORMATS");if(formats.contains("MP3128"))quality(qualitys,"128k",json.nullNode(),"present");if(formats.contains("MP3H"))quality(qualitys,"320k",json.nullNode(),"present");if(formats.contains("FLAC"))quality(qualitys,"flac",json.nullNode(),"present");}
            case "mg" -> {id=text(row,"songId","id");String copyright=text(row,"copyrightId");if(copyright.isEmpty())throw new IllegalArgumentException();title=text(row,"songName","name");singers=first(row,"singerList","singers","singer");JsonNode al=row.path("albums").path(0);album=text(row,"album","albumName");if(album.isEmpty())album=text(al,"name");albumId=text(row,"albumId");if(albumId.isEmpty())albumId=text(al,"id");cover=text(row,"img1","img","imageUrl","largePic");JsonNode images=row.path("imgItems");if(cover.isEmpty()&&images.isArray()&&!images.isEmpty())cover=text(images.get(0),"img","imgUrl");if(cover.startsWith("/")&&!cover.startsWith("//"))cover="https://d.musicapp.migu.cn"+cover;duration=millis(row.path("duration"),true);meta.put("songId",id);meta.put("copyrightId",copyright);meta.put("lrcUrl",text(row,"lrcUrl","lyricUrl"));for(var format:array(first(row,"audioFormats","newRateFormats","rateFormats"))){String q=switch(text(format,"formatType")){case "PQ"->"128k";case "HQ"->"320k";case "SQ"->"flac";case "ZQ","ZQ24"->"flac24bit";default->"";};if(!q.isEmpty())quality(qualitys,q,format.path("size"),"present");}}
            default -> throw new IllegalArgumentException();
        }
        List<String> artists=artists(singers);title=cleanText(title);if(id.isBlank()||id.length()>200||title.isBlank())throw new IllegalArgumentException();
        meta.put("albumName",cleanText(album));meta.put("albumId",albumId);meta.set("qualitys",qualitys);ObjectNode qualities=json.createObjectNode();for(var q:qualitys)qualities.set(q.path("type").asText(),q);meta.set("_qualitys",qualities);
        ObjectNode music=row.isObject()?row.deepCopy():json.createObjectNode();music.put("source",p);music.put("songmid",id);music.put("name",title);music.put("singer",String.join(" / ",artists));music.set("meta",meta);if(p.equals("kg"))music.put("hash",id);if(p.equals("tx"))music.put("strMediaMid",meta.path("strMediaMid").asText());
        return new Track(AuthCrypto.hash(p+":"+id),p,id,title,artists,cleanText(album),duration,safeCover(cover),music);
    }
    public String lyrics(Track track) {
        String p=track.platform(),id=track.remoteId();JsonNode root;
        switch(p) {
            case "wy" -> {root=get(url("https://music.163.com/api/song/lyric","id",id,"lv",1,"kv",1,"tv",-1),p);return root.path("lrc").path("lyric").asText("");}
            case "tx" -> {root=get(url("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg","songmid",id,"format","json","nobase64",1),p);return root.path("lyric").asText("");}
            case "kw" -> {root=get(url("https://mlyric.kuwo.cn/newh5/singles/songinfoandlrc","musicId",id),p).path("data");var out=new StringBuilder();for(var line:array(root.path("lrclist"))){double seconds=line.path("time").asDouble();out.append(String.format(Locale.ROOT,"[%02d:%05.2f]%s%n",(int)(seconds/60),seconds%60,line.path("lineLyric").asText()));}return out.toString();}
            case "kg" -> {root=get(url("https://lyrics.kugou.com/search","ver",1,"man","yes","client","pc","hash",id,"keyword",track.title(),"duration",track.durationMs()==null?0:track.durationMs()),p);JsonNode c=root.path("candidates").path(0);if(c.isMissingNode())return "";root=get(url("https://lyrics.kugou.com/download","ver",1,"client","pc","fmt","lrc","charset","utf8","id",c.path("id").asText(),"accesskey",c.path("accesskey").asText()),p);try{return new String(Base64.getDecoder().decode(root.path("content").asText()),StandardCharsets.UTF_8);}catch(Exception e){return "";}}
            case "mg" -> {String address=track.musicInfo().path("meta").path("lrcUrl").asText();if(address.isEmpty())return "";HttpUrl u=LxNetwork.checked(address,true);if(!u.host().endsWith(".migu.cn"))return "";var r=network.request(address,"GET",Map.of(),null,256*1024,true,"lx-lyrics-"+UUID.randomUUID());return r.status()==200?new String(r.bytes(),StandardCharsets.UTF_8):"";}
            default -> {return "";}
        }
    }
    private JsonNode get(String address,String platform) {
        return send(address,platform,"GET",Map.of(),null);
    }
    private JsonNode postForm(String address,String platform,Object... values){String query=url("https://example.com",values);return send(address,platform,"POST",Map.of("Content-Type","application/x-www-form-urlencoded"),query.substring(query.indexOf('?')+1).getBytes(StandardCharsets.UTF_8));}
    private String encodeJson(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw bad("目录参数无效");}}
    private JsonNode send(String address,String platform,String method,Map<String,String> extra,byte[] bodyBytes) {
        var headers=new HashMap<String,String>(Map.of("Referer",platform.equals("tx")?"https://y.qq.com/":platform.equals("wy")?"https://music.163.com/":"https://www.kuwo.cn/","User-Agent","Mozilla/5.0","Accept","application/json"));headers.putAll(extra);
        var reply=network.request(address,method,headers,bodyBytes,8*1024*1024,false,"lx-catalog-"+UUID.randomUUID());
        if(reply.status()!=200)throw unavailable(reply.status()==401||reply.status()==403?"该目录需要权限，请检查是否公开或更换平台":"平台请求失败，请稍后重试");
        try {
            String body=new String(reply.bytes(),StandardCharsets.UTF_8).trim();
            if(body.matches("(?s)^[A-Za-z_$][\\w.$]*\\s*\\(.*\\);?\\s*$"))body=body.substring(body.indexOf('(')+1,body.lastIndexOf(')'));
            JsonNode root=platform.equals("kw")
                    ?json.reader().with(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature()).readTree(body)
                    :json.readTree(body);String code=root.path("code").asText("");
            if(platform.equals("wy")&&!code.equals("200")||platform.equals("tx")&&!code.equals("0")&&!code.isEmpty()||platform.equals("kg")&&root.has("status")&&root.path("status").asInt()!=1||platform.equals("mg")&&!code.isEmpty()&&!Set.of("000000","0","200","00000000").contains(code))throw unavailable("该目录暂不可读取，可能已删除或需要登录权限");
            return root;
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw unavailable("平台响应发生变化，请稍后重试或更换平台");}
    }
    static String url(String base,Object... query){var b=Objects.requireNonNull(HttpUrl.parse(base)).newBuilder();for(int i=0;i<query.length;i+=2)b.addQueryParameter(query[i].toString(),query[i+1].toString());return b.build().toString();}
    static JsonNode first(JsonNode node,String... keys){for(String key:keys)if(node.hasNonNull(key))return node.path(key);return MissingNode.getInstance();}
    static String text(JsonNode node,String... keys){return first(node,keys).asText("");}
    static Iterable<JsonNode> array(JsonNode node){return node.isArray()?node:List.of();}
    private JsonNode slice(JsonNode rows,int page){var out=json.createArrayNode();int start=(page-1)*PAGE_SIZE;for(int i=start;i<Math.min(rows.size(),start+PAGE_SIZE);i++)out.add(rows.get(i));return out;}
    private static String cleanText(String value){String v=HtmlUtils.htmlUnescape(value.replaceAll("<[^>]*>","")).replaceAll("[\\x00-\\x1f]"," ").trim();return v.substring(0,Math.min(300,v.length()));}
    private static String safeCover(String value){try{if(value.startsWith("//"))value="https:"+value;return LxNetwork.checked(value,false).toString();}catch(Exception e){return "";}}
    private static List<String> artists(JsonNode value){var names=new LinkedHashSet<String>();if(value.isArray())for(var row:value){String name=cleanText(row.isObject()?text(row,"name","singerName"):row.asText());if(!name.isEmpty())names.add(name);}else if(value.isTextual())for(String name:value.asText().split("[;&、]")){name=cleanText(name);if(!name.isEmpty())names.add(name);}return names.isEmpty()?List.of("未知歌手"):List.copyOf(names);}
    private static Long millis(JsonNode value,boolean seconds){try{double result;if(value.asText().contains(":")){result=0;for(String part:value.asText().split(":"))result=result*60+Double.parseDouble(part);result*=1000;}else result=Double.parseDouble(value.asText())*(seconds?1000:1);return Double.isFinite(result)&&result>0&&result<604800000?(long)result:null;}catch(Exception e){return null;}}
    private void quality(ArrayNode rows,String type,JsonNode size,String hash){if(size.asLong()>0||hash!=null&&!hash.isEmpty()){ObjectNode q=rows.addObject();q.put("type",type);if(size.asLong()>0)q.put("size",size.asLong());if(hash!=null&&!hash.equals("present"))q.put("hash",hash);}}
    static RuntimeException unavailable(String message){return new LxNetwork.LxNetworkFailure(message);}
}
