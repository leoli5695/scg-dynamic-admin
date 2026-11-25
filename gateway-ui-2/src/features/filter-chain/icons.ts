/**
 * Page-local icons not present in the shared console set (components/icons is
 * owned by the global session). Same Lucide geometry via the shared adapter.
 */
import { Image } from 'lucide-vue-next'
import { createIcon } from '@/components/icons/createIcon'

export const PictureOutlined = createIcon('PictureOutlined', Image)