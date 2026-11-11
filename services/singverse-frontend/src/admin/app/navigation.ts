import { Clock, Collection, CollectionTag, Connection, Download, Files, FolderOpened, Headset, House, Key, MagicStick, Microphone, Monitor, Odometer, Setting, ChatLineSquare } from '@element-plus/icons-vue'

/** 以普通用户的任务组织入口；既有 hash 地址、查询参数及 activeMenu 继续生效。 */
export const overviewEntry = { path: '/overview', label: '首页', icon: House }
export const taskEntry = { path: '/system/tasks', label: '任务中心', icon: Clock }
export const navigationGroups = [
  { id: 'library', label: '曲库', path: '/library/songs', icon: Headset, children: [
    { path: '/library/songs', label: '歌曲', icon: Headset },
    { path: '/library/artists', label: '歌手', icon: Microphone },
    { path: '/library/playlists', label: '歌单', icon: Collection },
    { path: '/library/classification', label: '分类标签', icon: CollectionTag },
  ] },
  { id: 'add', label: '加歌', path: '/library/media', icon: Download, children: [
    { path: '/library/media', label: '歌曲来源', icon: FolderOpened },
    { path: '/library/downloads', label: '下载歌曲', icon: Download },
    { path: '/library/media/recognition', label: '文件名识别', icon: MagicStick },
  ] },
  { id: 'tidy', label: '整理', path: '/library/songs/metadata', icon: MagicStick, children: [
    { path: '/library/songs/metadata', label: '歌曲信息', icon: MagicStick },
    { path: '/library/audio-processing', label: '原唱与伴奏', icon: Headset },
    { path: '/library/review', label: '待审核', icon: CollectionTag },
  ] },
  { id: 'living', label: '客厅', path: '/system/tv-devices', icon: Monitor, children: [
    { path: '/system/tv-devices', label: '电视连接', icon: Monitor },
    { path: '/system/tv-settings', label: '电视首页', icon: Monitor },
    { path: '/library/playback-history', label: '播放与热门', icon: Clock },
    { path: '/library/missing-feedback', label: '缺歌心愿', icon: ChatLineSquare },
  ] },
  { id: 'settings', label: '设置', path: '/system/parameters', icon: Setting, children: [
    { path: '/system/parameters', label: '账号与安全', icon: Key },
    { path: '/system/music-platforms', label: '音乐平台', icon: Headset },
    { path: '/library/cache', label: '存储与缓存', icon: Files },
    { path: '/system/compute', label: '计算与模型', icon: Connection },
    { path: '/system/ai', label: 'AI 与助手', icon: MagicStick },
    { path: '/system', label: '服务状态', icon: Odometer },
  ] },
]
export function navigationContext(path: string) {
  if (path === '/library/media/recognition/rules' || path === '/library/filename-parser' || path === '/library/filename-parser-profiles') path = '/library/media/recognition'
  else if (path.startsWith('/library/media/') && path !== '/library/media/recognition') path = '/library/media'
  const group = navigationGroups.find(group => group.children.some(entry => entry.path === path))
  return { group, entry: group?.children.find(entry => entry.path === path) ?? (path === overviewEntry.path ? overviewEntry : path === taskEntry.path || path.startsWith(taskEntry.path + '/') ? taskEntry : undefined) }
}