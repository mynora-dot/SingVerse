import { CollectionTag, Document, FolderOpened, Headset, House, MagicStick, Monitor, Setting, Tools } from '@element-plus/icons-vue'

/** User-facing navigation order; URLs stay compatible with saved links. */
export const overviewEntry = { path: '/overview', label: '概览', icon: House }
export const navigationGroups = [
  {
    id: 'library', label: '曲库管理', icon: Headset,
    children: [
      { path: '/library/songs', label: '全部歌曲', icon: Headset },
      { path: '/library/playlists', label: '歌单管理', icon: CollectionTag },
      { path: '/library/artists', label: '歌手库', icon: Headset },
      { path: '/library/media', label: '歌曲位置', icon: FolderOpened },
      { path: '/library/songs/metadata', label: '元数据整理', icon: MagicStick },
      { path: '/library/classification', label: '歌曲分类', icon: CollectionTag },
    ],
  },
  {
    id: 'parsing', label: '文件名解析', icon: Document,
    children: [
      { path: '/library/filename-parser-profiles', label: '解析方案', icon: Document },
      { path: '/library/filename-parser', label: '自定义规则', icon: Tools },
    ],
  },
  {
    id: 'system', label: '系统设置', icon: Setting,
    children: [
      { path: '/system/ai', label: 'AI 设置', icon: MagicStick },
      { path: '/system/tv-devices', label: '电视设备', icon: Monitor },
      { path: '/system/compute', label: '性能节点配置', icon: Monitor },
      { path: '/system', label: '服务状态', icon: Monitor },
      { path: '/system/parameters', label: '账号与安全', icon: Setting },
    ],
  },
]

export function navigationContext(path: string) {
  const group = navigationGroups.find(group => group.children.some(entry => entry.path === path))
  return { group, entry: group?.children.find(entry => entry.path === path) ?? (path === overviewEntry.path ? overviewEntry : undefined) }
}
