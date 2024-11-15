import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

class WebViewExample extends StatefulWidget {
  const WebViewExample({super.key});

  @override
  State<StatefulWidget> createState() {
    return _WebViewExampleState();
  }
}

class _WebViewExampleState extends State<WebViewExample> {
  late final WebViewController _controller;

  @override
  void initState() {
    super.initState();

    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(NavigationDelegate(
        onProgress: (int progress) {
          // progress
          // print("[progress] $progress");
        },
        onPageStarted: (String url) {
          // print("[onPageStarted] $url");
        },
        onPageFinished: (String url) {
          // print("[onPageFinished] $url");
        },
        // onHttpError: (HttpResponseError error) {},
        onWebResourceError: (WebResourceError error) {
          // print("[onWebResourceError] ${error.toString()}");
        },
        onNavigationRequest: (NavigationRequest request) {
          // print("[onNavigationRequest] ${request.url}");
          // if (request.url.startsWith('https://www.youtube.com/')) {
          //   return NavigationDecision.prevent;
          // }
          return NavigationDecision.navigate;
        },
      ));

    // #docregion platform_features
    if (_controller.platform is AndroidWebViewController) {
      AndroidWebViewController.enableDebugging(true);
      (_controller.platform as AndroidWebViewController)
          .setMediaPlaybackRequiresUserGesture(false);
    }

    _controller.loadRequest(Uri.parse(
        "https://airplay-tv.pages.dev/#/video/qr?_from=tv&_platform=flutter"));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: WebViewWidget(
        controller: _controller,
      ),
    );
  }
}
