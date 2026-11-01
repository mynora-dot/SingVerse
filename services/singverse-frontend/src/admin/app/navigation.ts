import { Collection, CollectionTag, Connection, Document, FolderOpened, Headset, House, Key, MagicStick, Microphone, Monitor, Odometer, Setting, Tools } from '@element-plus/icons-vue'

/** 按管理员日常任务分组；URL 保持不变以兼容已保存的链接。 */
export const overviewEntry = { path: '/overview', label: '概览', icon: House }
export const navigationGroups = [
  {
    id: 'library', label: '曲库',
    children: [
      { path: '/library/songs', label: '全部歌曲', icon: Headset },
      { path: '/library/audio-processing', label: '原唱与伴奏', icon: Headset },
      { path: '/library/artists', label: '歌手库', icon: Microphone },
      { path: '/library/playlists', label: '歌单管理', icon: Collection },
      { path: '/library/classification', label: '歌曲分类', icon: CollectionTag },
      { path: '/library/songs/metadata', label: '元数据整理', icon: MagicStick },
    ],
  },
  {
    id: 'sources', label: '来源与导入',
    children: [
      { path: '/library/media', label: '媒体库', icon: FolderOpened },
      { path: '/library/filename-parser-profiles', label: '解析方案', icon: Document },
      { path: '/library/filename-parser', label: '自定义规则', icon: Tools, advanced: true },
    ],
  },
  {
    id: 'devices', label: '设备与服务',
    children: [
      { path: '/system/tv-devices', label: '电视设备', icon: Monitor },
      { path: '/system', label: '服务状态', icon: Odometer },
      { path: '/system/compute', label: '性能节点配置', icon: Connection },
    ],
  },
  {
    id: 'system', label: '系统设置',
    children: [
      { path: '/system/ai', label: 'AI 设置', icon: Setting },
      { path: '/system/parameters', label: '账号与安全', icon: Key },
    ],
  },
]

export function navigationContext(path: string) {
  const group = navigationGroups.find(group => group.children.some(entry => entry.path === path))
  return { group, entry: group?.children.find(entry => entry.path === path) ?? (path === overviewEntry.path ? overviewEntry : undefined) }
}
