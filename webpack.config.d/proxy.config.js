if (!config.devServer) {
  config.devServer = {}
}
config.devServer.proxy = {
  '/downloadWorkerImage.php': {
    target: 'https://mecitech.com',
    changeOrigin: true,
    secure: false
  },
  '/uploadWorkerImage.php': {
    target: 'https://mecitech.com',
    changeOrigin: true,
    secure: false
  }
}
