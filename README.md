# spring-kotlin-gyakubiki

## setup

- IDEA の設定
  - IDE Remote Control プラグインを導入
  - 設定、「ビルド、実行、デプロイ」、デバッガーを選択
  - ビルトインサーバー、ポートを 63342、署名されていない要求を許可する
- 静的解析を実行
  - 「どこでも検索」から、IDE スクリプトコンソールを選択。言語は Kotlin を選択
  - analyseProject.kt を貼り付けて、すべて選択して Ctrl + Enter で実行
  - 実行結果の JSON を Web アプリのテキストエリアに貼り付ける
