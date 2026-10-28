"""Small Flask adapter for the existing NiceExcel API handlers.

This is a Flask application; the adapter preserves handler signatures and Pydantic
request models while replacing the FastAPI/Uvicorn runtime with Flask's WSGI server.
"""
from __future__ import annotations
import asyncio
import inspect
from typing import get_type_hints
from pathlib import Path
from flask import Flask, request as flask_request, jsonify, send_file, send_from_directory, Response

class HTTPException(Exception):
    def __init__(self, status_code=500, detail="Request failed"):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail

class _Query:
    def __init__(self, default=None, alias=None): self.default, self.alias = default, alias

def Query(default=None, alias=None): return _Query(default, alias)
def File(default=None): return default

class UploadFile:
    def __init__(self, storage):
        self._storage = storage
        self.filename = storage.filename
    async def read(self, size=-1): return self._storage.stream.read(size)
    async def close(self):
        try: self._storage.close()
        except Exception: pass

class BackgroundTask:
    def __init__(self, func, *args, **kwargs): self.func, self.args, self.kwargs = func, args, kwargs
    def __call__(self): self.func(*self.args, **self.kwargs)

class _FlaskApp(Flask):
    version = "0.1.0"
    def get(self, path): return self._route(path, ["GET"])
    def post(self, path): return self._route(path, ["POST"])
    def patch(self, path): return self._route(path, ["PATCH"])
    def delete(self, path): return self._route(path, ["DELETE"])
    def _route(self, path, methods):
        flask_path = path
        import re
        flask_path = re.sub(r"\{([A-Za-z_][A-Za-z0-9_]*)\}", r"<\1>", flask_path)
        def decorator(fn):
            sig = inspect.signature(fn)
            try: hints = get_type_hints(fn)
            except Exception: hints = {}
            async_fn = inspect.iscoroutinefunction(fn)
            def handler(**path_values):
                try:
                    args = {}
                    body_cache = None
                    for name, param in sig.parameters.items():
                        if name in path_values:
                            args[name] = path_values[name]
                            continue
                        default = param.default
                        if isinstance(default, _Query):
                            args[name] = flask_request.args.get(default.alias or name, default.default)
                        elif param.annotation is UploadFile or name == "file":
                            storage = flask_request.files.get(name) or next(iter(flask_request.files.values()), None)
                            if storage is None:
                                raise HTTPException(400, "Required uploaded file is missing")
                            args[name] = UploadFile(storage)
                        elif name in ("request",) or (inspect.isclass(param.annotation) and hasattr(param.annotation, "parse_obj")):
                            if body_cache is None:
                                body_cache = flask_request.get_json(silent=True) or {}
                            model = hints.get(name, param.annotation)
                            try: args[name] = model.parse_obj(body_cache)
                            except Exception as exc: raise HTTPException(422, f"Invalid request body: {exc}") from exc
                        elif name in flask_request.args:
                            args[name] = flask_request.args.get(name)
                        elif default is not inspect.Parameter.empty:
                            args[name] = default
                    result = asyncio.run(fn(**args)) if async_fn else fn(**args)
                    if isinstance(result, Response): return result
                    if isinstance(result, dict) or isinstance(result, list): return jsonify(result)
                    return result
                except HTTPException as exc:
                    return jsonify({"detail": exc.detail}), exc.status_code
                except Exception as exc:
                    return jsonify({"detail": f"Internal server error: {type(exc).__name__}: {exc}"}), 500
            handler.__name__ = fn.__name__
            self.add_url_rule(flask_path, endpoint=f"{fn.__name__}_{len(self.view_functions)}", view_func=handler, methods=methods)
            return fn
        return decorator

def create_app(import_name):
    app = _FlaskApp(import_name)
    return app

def FileResponse(path, media_type=None, filename=None, background=None):
    response = send_file(path, mimetype=media_type, as_attachment=bool(filename), download_name=filename)
    if background:
        response.call_on_close(background)
    return response

def StreamingResponse(content, media_type=None, headers=None):
    response = Response(content, mimetype=media_type, headers=headers)
    return response
