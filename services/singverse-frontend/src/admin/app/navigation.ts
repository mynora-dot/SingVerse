import { Clock, Collection, CollectionTag, Connection, Download, Files, FolderOpened, Headset, House, Key, MagicStick, Microphone, Monitor, Odometer, Setting, ChatLineSquare } from '@element-plus/icons-vue'

/** 以任务组织入口；既有 hash 地址、查询参数及 activeMenu 继续生效。 */
export const overviewEntry = { path: '/overview', label: '我的音乐空间', icon: House }
export const taskEntry = { path: '/system/tasks', label: '任务中心', icon: Clock }
export const navigationGroups = [
  { id: 'library', label: '曲库', path: '/library/songs', icon: Headset, children: [
    { path: '/library/songs', label: '全部歌曲', icon: Headset },
    { path: '/library/artists', label: '歌手', icon: Microphone },
    { path: '/library/playlists', label: '共享歌单', icon: Collection },
  ] },
  { id: 'studio', label: '添加与整理', path: '/library/media', icon: MagicStick, children: [
    { path: '/library/media', label: '歌曲来源', icon: FolderOpened },
    { path: '/library/downloads', label: '下载歌曲', icon: Download },
    { path: '/library/songs/metadata', label: '歌曲信息', icon: MagicStick },
    { path: '/library/audio-processing', label: '原唱与伴奏', icon: Headset },
    { path: '/library/classification', label: '分类与标签', icon: CollectionTag },
  ] },
  { id: 'television', label: '电视与反馈', path: '/system/tv-devices', icon: Monitor, children: [
    { path: '/system/tv-devices', label: '连接电视', icon: Monitor },
    { path: '/system/tv-settings', label: '电视首页', icon: Monitor },
    { path: '/library/playback-history', label: '播放与热门', icon: Clock },
    { path: '/library/missing-feedback', label: '缺歌心愿', icon: ChatLineSquare },
  ] },
  { id: 'settings', label: '设置', path: '/system/parameters', icon: Setting, children: [
    { path: '/system/parameters', label: '账号与安全', icon: Key },
    { path: '/library/cache', label: '存储与缓存', icon: Files },
    { path: '/system/compute', label: '计算与模型', icon: Connection },
    { path: '/system/ai', label: 'AI 与助手', icon: MagicStick },
    { path: '/system', label: '服务状态', icon: Odometer },
  ] },
]
export function navigationContext(path: string) {
  if (path.startsWith('/library/media/') || path === '/library/filename-parser-profiles' || path === '/library/filename-parser') path = '/library/media'
  const group = navigationGroups.find(group => group.children.some(entry => entry.path === path))
  return { group, entry: group?.children.find(entry => entry.path === path) ?? (path === overviewEntry.path ? overviewEntry : path === taskEntry.path || path.startsWith(taskEntry.path + '/') ? taskEntry : undefined) }
}
