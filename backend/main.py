"""
JMReader 后端 —— 使用 FastAPI 封装 jmcomic 库，为安卓客户端提供 REST 接口。

运行方式：
    cd backend
    pip install -r requirements.txt
    python main.py
    # 默认监听 127.0.0.1:8000（仅本机）；需局域网访问设置 JM_HOST=0.0.0.0

说明：
- 站点经常换域，请先在 option.yaml 中配置 domain；或通过环境变量
  JM_DOMAIN 指定（如 JM_DOMAIN=18comic.vip）。
- 登录态保存在进程内存，所有接口可共享同一 client。
- 图片走后端代理下载并解密，避免 App 直连站点被风控，同时统一处理分割图。
"""
from __future__ import annotations

import io
import logging
import os
import threading
from typing import Optional
from urllib.parse import urlparse

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel
import uvicorn

logger = logging.getLogger("jmreader.backend")

try:
    import jmcomic
    from jmcomic.jm_exception import JmcomicException
except ImportError:  # 便于在未装库时仍能启动查看接口
    jmcomic = None
    JmcomicException = Exception

# ---------------------------------------------------------------------------
# jmcomic 客户端初始化
# ---------------------------------------------------------------------------

DOMAIN = os.environ.get("JM_DOMAIN", "18comic.vip")
DOWNLOAD_DIR = os.environ.get("JM_DOWNLOAD_DIR", os.path.join(os.getcwd(), "downloads"))
LOGIN_USER = os.environ.get("JM_USER", "")
LOGIN_PASS = os.environ.get("JM_PASS", "")

_lock = threading.Lock()
_option = None
_client = None


def _build_option():
    """构建 jmcomic 配置。优先读 option.yaml，否则用默认配置。"""
    yaml_path = os.path.join(os.path.dirname(__file__), "option.yaml")
    if os.path.exists(yaml_path):
        try:
            return jmcomic.JmOption.from_file(yaml_path)
        except Exception as e:
            logger.debug("加载 option.yaml 失败，使用默认配置", exc_info=True)
    # 默认配置：不下载原图、使用移动端接口
    option = jmcomic.JmOption.default()
    try:
        option.impl_api = "api"
    except Exception as e:
        logger.debug("设置 impl_api 失败", exc_info=True)
    option.download.dir = DOWNLOAD_DIR
    return option


def get_client():
    global _option, _client
    if _client is not None:
        return _client
    if jmcomic is None:
        raise HTTPException(status_code=500, detail="jmcomic 库未安装，请在后端执行 pip install -r requirements.txt")
    with _lock:
        if _client is None:
            _option = _build_option()
            try:
                _client = _option.new_jm_client()
            except Exception as e:
                # 不同版本 API 名略有差异，做一层兜底
                try:
                    _client = jmcomic.JmApiClient.default()
                except Exception:
                    raise HTTPException(status_code=500, detail=f"初始化 jmcomic 客户端失败: {e}")
            if LOGIN_USER and LOGIN_PASS:
                try:
                    _client.login(LOGIN_USER, LOGIN_PASS)
                except Exception as e:
                    logger.debug("登录失败", exc_info=True)  # 登录失败不阻断启动
    return _client


# ---------------------------------------------------------------------------
# 响应模型
# ---------------------------------------------------------------------------

class ComicBrief(BaseModel):
    id: str
    name: str
    author: Optional[str] = None
    tags: list[str] = []
    cover: Optional[str] = None
    likes: Optional[str] = None
    views: Optional[str] = None
    page_count: Optional[int] = None


class PageResult(BaseModel):
    page: int
    total: Optional[int] = None
    items: list[ComicBrief] = []


class Chapter(BaseModel):
    id: str
    title: str
    sort: int = 0


class ComicDetail(BaseModel):
    id: str
    name: str
    author: Optional[str] = None
    description: Optional[str] = None
    tags: list[str] = []
    cover: Optional[str] = None
    likes: Optional[str] = None
    views: Optional[str] = None
    chapters: list[Chapter] = []


class ChapterImages(BaseModel):
    id: str
    title: Optional[str] = None
    scramble_id: Optional[str] = None
    images: list[str] = []  # 这里返回的是后端可代理的相对 URL /api/img?url=...


class LoginReq(BaseModel):
    username: str
    password: str


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

# 允许图片代理的域名后缀白名单（防止 SSRF：禁止请求内网/云元数据等地址）
_ALLOWED_IMAGE_HOST_SUFFIXES = (
    ".jmcomic.me",
    ".jmcomic1.me",
    ".jmcomic2.me",
    ".18comic.vip",
    ".18comic.org",
    ".18-comic.org",
    ".jm365.cc",
    ".cdn-assets.cc",
)


def _is_allowed_image_url(url: str) -> bool:
    """校验图片 URL 是否属于允许的域名白名单，防止 SSRF。"""
    try:
        parsed = urlparse(url)
    except Exception:
        return False
    if parsed.scheme not in ("http", "https"):
        return False
    host = (parsed.hostname or "").lower()
    if not host:
        return False
    # 禁止内网地址
    if host in ("localhost", "0.0.0.0", "::1") or host.startswith("127.") or host.startswith("10.") \
            or host.startswith("192.168.") or host.startswith("169.254.") \
            or host.startswith("172."):
        return False
    return any(host == s.lstrip(".") or host.endswith(s) for s in _ALLOWED_IMAGE_HOST_SUFFIXES)


def _safe_str(v, default="") -> str:
    if v is None:
        return default
    try:
        return str(v)
    except Exception:
        return default


def _brief_from_album(album) -> ComicBrief:
    """从 jmcomic 的 album 简略对象构造 brief。"""
    tags = []
    try:
        tags = [t for t in _safe_str(getattr(album, "tags", "")).split(",") if t]
    except Exception as e:
        logger.debug("解析 tags 字符串失败", exc_info=True)
    if not tags:
        try:
            tags = list(getattr(album, "tags_list", []) or [])
        except Exception as e:
            logger.debug("获取 tags_list 失败", exc_info=True)
    cover = _safe_str(getattr(album, "cover", "") or getattr(album, "cover_url", ""))
    if cover and not cover.startswith("http"):
        cover = f"https://{DOMAIN}/{cover.lstrip('/')}"
    return ComicBrief(
        id=_safe_str(getattr(album, "id", getattr(album, "album_id", ""))),
        name=_safe_str(getattr(album, "name", getattr(album, "title", ""))),
        author=_safe_str(getattr(album, "author", "")) or None,
        tags=tags,
        cover=cover or None,
        likes=_safe_str(getattr(album, "likes", "")) or None,
        views=_safe_str(getattr(album, "views", "")) or None,
        page_count=getattr(album, "page_count", None),
    )


# ---------------------------------------------------------------------------
# FastAPI 路由
# ---------------------------------------------------------------------------

app = FastAPI(title="JMReader Backend", version="1.0.0")


@app.get("/api/health")
def health():
    return {"ok": True, "jmcomic": jmcomic is not None, "domain": DOMAIN}


@app.get("/api/search", response_model=PageResult)
def search(
    q: str = Query("", description="关键词"),
    page: int = Query(1, ge=1),
    order: str = Query("latest", description="latest|views|likes|picture"),
    time: str = Query("all", description="all|today|week|month"),
    category: str = Query("", description="分类ID"),
):
    c = get_client()
    try:
        order_map = {
            "latest": jmcomic.JmSearchOrderBy.LATEST if hasattr(jmcomic, "JmSearchOrderBy") else "latest",
            "views": getattr(getattr(jmcomic, "JmSearchOrderBy", None), "VIEW", "views"),
            "likes": getattr(getattr(jmcomic, "JmSearchOrderBy", None), "LIKE", "likes"),
            "picture": getattr(getattr(jmcomic, "JmSearchOrderBy", None), "PICTURE", "picture"),
        }
        result = c.search_album(
            search_query=q,
            page=page,
            order_by=order_map.get(order, order),
            time=time,
            category=category or None,
        )
    except TypeError:
        # 老版本签名兼容
        result = c.search_album(q, page=page)
    except JmcomicException as e:
        raise HTTPException(status_code=502, detail=str(e))

    items = []
    content = getattr(result, "content", None) or getattr(result, "list", None) or []
    for a in content:
        try:
            items.append(_brief_from_album(a))
        except Exception as e:
            logger.debug("构造 brief 失败，跳过", exc_info=True); continue
    total = getattr(result, "page_total", None) or getattr(result, "total", None)
    return PageResult(page=page, total=total, items=items)


@app.get("/api/categories")
def categories():
    """返回常用分类。category 是 slug 字符串（移动端 API 协议），不是数字 ID。
    对应 jmcomic.JmMagicConstants.CATEGORY_*。"""
    static = [
        {"id": "0", "name": "全部"},
        {"id": "doujin", "name": "同人"},
        {"id": "single", "name": "单本"},
        {"id": "short", "name": "短篇"},
        {"id": "hanman", "name": "韩漫"},
        {"id": "meiman", "name": "美漫"},
        {"id": "doujin_cosplay", "name": "Cosplay"},
        {"id": "3D", "name": "3D"},
        {"id": "another", "name": "其他"},
        {"id": "english_site", "name": "英文站"},
    ]
    return {"items": static}


@app.get("/api/ranking", response_model=PageResult)
def ranking(
    time: str = Query("all", description="all|today|week|month"),
    category: str = Query(""),
    page: int = Query(1, ge=1),
):
    """排行：复用搜索接口（按观看排序）。"""
    return search(q="", page=page, order="views", time=time, category=category)


@app.get("/api/latest", response_model=PageResult)
def latest(page: int = Query(1, ge=1), category: str = Query("")):
    """最新更新列表。"""
    return search(q="", page=page, order="latest", time="all", category=category)


@app.get("/api/comic/{album_id}", response_model=ComicDetail)
def comic_detail(album_id: str):
    c = get_client()
    try:
        album = c.get_album_detail(album_id)
    except JmcomicException as e:
        raise HTTPException(status_code=502, detail=str(e))

    tags = []
    try:
        tags = [t for t in _safe_str(getattr(album, "tags", "")).split(",") if t]
    except Exception as e:
        logger.debug("解析 tags 失败", exc_info=True)
    if not tags:
        try:
            tags = list(getattr(album, "tags_list", []) or [])
        except Exception as e:
            logger.debug("获取 tags_list 失败", exc_info=True)

    cover = _safe_str(getattr(album, "cover", "") or getattr(album, "cover_url", ""))
    if cover and not cover.startswith("http"):
        cover = f"https://{DOMAIN}/{cover.lstrip('/')}"

    chapters = []
    eps = getattr(album, "eps", None) or getattr(album, "chapters", None) or []
    try:
        for i, ch in enumerate(eps):
            chapters.append(Chapter(
                id=_safe_str(getattr(ch, "chapter_id", getattr(ch, "id", ""))),
                title=_safe_str(getattr(ch, "title", getattr(ch, "name", ""))) or f"第{i+1}章",
                sort=i,
            ))
    except Exception as e:
        logger.debug("遍历章节失败", exc_info=True)
    if not chapters:
        # 单章节漫画：自身即唯一章节
        chapters.append(Chapter(id=album_id, title=_safe_str(getattr(album, "name", "")) or "正文", sort=0))

    return ComicDetail(
        id=album_id,
        name=_safe_str(getattr(album, "name", getattr(album, "title", ""))),
        author=_safe_str(getattr(album, "author", "")) or None,
        description=_safe_str(getattr(album, "description", getattr(album, "intro", ""))) or None,
        tags=tags,
        cover=cover or None,
        likes=_safe_str(getattr(album, "likes", "")) or None,
        views=_safe_str(getattr(album, "views", "")) or None,
        chapters=chapters,
    )


@app.get("/api/chapter/{chapter_id}/images", response_model=ChapterImages)
def chapter_images(chapter_id: str):
    c = get_client()
    try:
        photo = c.get_photo_detail(chapter_id)
    except JmcomicException as e:
        raise HTTPException(status_code=502, detail=str(e))

    images = []
    raw = getattr(photo, "images", None) or []
    for img in raw:
        url = _safe_str(getattr(img, "url", "") or getattr(img, "img_url", ""))
        if url and not url.startswith("http"):
            url = f"https://{DOMAIN}/{url.lstrip('/')}"
        if url:
            images.append(url)
    if not images:
        # 某些版本直接是字符串列表
        try:
            images = [u for u in raw if isinstance(u, str)]
        except Exception as e:
            logger.debug("解析 raw 图片列表失败", exc_info=True)

    return ChapterImages(
        id=chapter_id,
        title=_safe_str(getattr(photo, "name", getattr(photo, "title", ""))) or None,
        scramble_id=_safe_str(getattr(photo, "scramble_id", "")) or None,
        images=images,
    )


@app.get("/api/img")
def proxy_image(url: str = Query(...)):
    """代理下载并解密图片，返回 image/* 给客户端直接用。

    jmcomic 内部会处理分割/解密，这里用 image_downloader 复用其逻辑。
    """
    # SSRF 防护：只允许禁漫相关域名的图片 URL
    if not _is_allowed_image_url(url):
        raise HTTPException(status_code=403, detail="URL 域名不在允许列表内")
    c = get_client()
    try:
        # 复用 jmcomic 的图片下载+解密能力
        downloader = getattr(c, "download_image", None)
        if downloader is None:
            # 老版本通过 option
            downloader = getattr(_option, "download_image", None)
        if downloader is None:
            # 兜底：直接请求原始 URL（不解密，仅当图片未加密时可用）
            import requests
            r = requests.get(url, timeout=20)
            r.raise_for_status()
            data = r.content
            content_type = r.headers.get("Content-Type", "image/jpeg")
            return StreamingResponse(io.BytesIO(data), media_type=content_type)
        # 调用 download_image(url, save_path) 解密后写盘，再读回
        import tempfile
        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as tmp:
            save_path = tmp.name
        downloader(url, save_path)
        with open(save_path, "rb") as f:
            data = f.read()
        try:
            os.remove(save_path)
        except Exception as e:
            logger.debug("删除临时文件失败", exc_info=True)
        return StreamingResponse(io.BytesIO(data), media_type="image/jpeg")
    except JmcomicException as e:
        raise HTTPException(status_code=502, detail=str(e))


@app.post("/api/login")
def login(req: LoginReq):
    c = get_client()
    try:
        resp = c.login(req.username, req.password)
    except JmcomicException as e:
        raise HTTPException(status_code=401, detail=str(e))
    ok = bool(getattr(resp, "is_success", True) if resp is not None else True)
    return {"ok": ok, "msg": _safe_str(getattr(resp, "msg", ""))}


@app.post("/api/logout")
def logout():
    global _client
    _client = None
    return {"ok": True}


@app.get("/api/favorites", response_model=PageResult)
def favorites(page: int = Query(1, ge=1)):
    c = get_client()
    try:
        result = c.favorite_folder(page=page)
    except (AttributeError, JmcomicException) as e:
        raise HTTPException(status_code=502, detail=f"获取收藏失败: {e}")

    items = []
    content = getattr(result, "content", None) or getattr(result, "list", None) or []
    for a in content:
        try:
            items.append(_brief_from_album(a))
        except Exception as e:
            logger.debug("构造 brief 失败，跳过", exc_info=True); continue
    total = getattr(result, "page_total", None)
    return PageResult(page=page, total=total, items=items)


@app.post("/api/favorite/{album_id}")
def add_favorite(album_id: str):
    c = get_client()
    try:
        c.add_favorite(album_id)
    except (AttributeError, JmcomicException) as e:
        raise HTTPException(status_code=502, detail=str(e))
    return {"ok": True}


@app.delete("/api/favorite/{album_id}")
def remove_favorite(album_id: str):
    c = get_client()
    try:
        c.del_favorite(album_id)
    except (AttributeError, JmcomicException) as e:
        raise HTTPException(status_code=502, detail=str(e))
    return {"ok": True}


@app.post("/api/download/{album_id}")
def download_album(album_id: str):
    """触发后端整本下载（异步）。下载内容存于 DOWNLOAD_DIR，App 也可走 /api/img 走流式。"""
    if jmcomic is None:
        raise HTTPException(status_code=500, detail="jmcomic 未安装")
    threading.Thread(
        target=lambda: jmcomic.download_album(album_id, _option or _build_option()),
        daemon=True,
    ).start()
    return {"ok": True, "msg": "已加入下载队列"}


@app.exception_handler(Exception)
def all_exception(_: object, e: Exception):
    return JSONResponse(status_code=500, content={"detail": str(e)})


if __name__ == "__main__":
    # 默认仅监听本机回环地址，防止公网暴露；需局域网访问时设置 JM_HOST=0.0.0.0
    host = os.environ.get("JM_HOST", "127.0.0.1")
    port = int(os.environ.get("JM_PORT", "8000"))
    uvicorn.run("main:app", host=host, port=port, reload=False)
