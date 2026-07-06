import type { ComicBrief } from '@/api/types';
export declare function listFavorites(): Promise<ComicBrief[]>;
export declare function isFavorited(id: string): Promise<boolean>;
export declare function addFavorite(comic: ComicBrief): Promise<void>;
export declare function removeFavorite(id: string): Promise<void>;
export declare function toggleFavorite(comic: ComicBrief): Promise<boolean>;
