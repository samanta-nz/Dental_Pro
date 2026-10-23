package com.example.sync

class AccountMismatchException : IllegalStateException(
    "اطلاعات این دستگاه به حساب گوگل دیگری وابسته است. با همان حساب قبلی وارد شوید و همگام‌سازی را کامل کنید."
)
