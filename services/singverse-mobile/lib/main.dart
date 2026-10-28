import 'package:flutter/material.dart';

void main() => runApp(const SingVerseApp());

class SingVerseApp extends StatelessWidget {
  const SingVerseApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SingVerse',
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.indigo),
      home: const Scaffold(
        body: SafeArea(
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('SingVerse', style: TextStyle(fontSize: 32)),
                SizedBox(height: 16),
                Text('Welcome to SingVerse'),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
