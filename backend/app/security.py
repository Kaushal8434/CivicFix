"""Password hashing (PBKDF2-SHA256), JWT tokens and role checks."""
import hashlib
import hmac
import secrets
import time

import jwt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from .config import SECRET_KEY, TOKEN_DAYS
from .db import get_db
from .models import User

_ITERATIONS = 200_000
_bearer = HTTPBearer(auto_error=False)


def hash_password(password: str) -> str:
    salt = secrets.token_bytes(16)
    digest = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, _ITERATIONS)
    return f"pbkdf2_sha256${_ITERATIONS}${salt.hex()}${digest.hex()}"


def verify_password(password: str, stored: str) -> bool:
    try:
        _, iters, salt, digest = stored.split("$")
        test = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(salt), int(iters))
        return hmac.compare_digest(test.hex(), digest)
    except (ValueError, TypeError):
        return False


def create_token(user: User) -> str:
    now = int(time.time())
    return jwt.encode({"sub": str(user.id), "role": user.role, "iat": now, "exp": now + TOKEN_DAYS * 86400},
                      SECRET_KEY, algorithm="HS256")


def current_user_optional(cred: HTTPAuthorizationCredentials | None = Depends(_bearer),
                          db: Session = Depends(get_db)) -> User | None:
    if cred is None:
        return None
    try:
        payload = jwt.decode(cred.credentials, SECRET_KEY, algorithms=["HS256"])
    except jwt.PyJWTError:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Session expired – please sign in again")
    user = db.get(User, int(payload["sub"]))
    if user is None or not user.active:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Account not found or disabled")
    return user


def current_user(user: User | None = Depends(current_user_optional)) -> User:
    if user is None:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Please sign in")
    return user


def require_roles(*roles: str):
    def dep(user: User = Depends(current_user)) -> User:
        if user.role not in roles:
            raise HTTPException(status.HTTP_403_FORBIDDEN, "You do not have access to this")
        return user
    return dep


is_staff = require_roles("officer", "supervisor", "admin")
is_supervisor = require_roles("supervisor", "admin")
is_admin = require_roles("admin")
