import 'package:dio/dio.dart';

class BankAccountService {
  final Dio _dio;
  BankAccountService(this._dio);

  Future<List<dynamic>> getBankAccounts() async {
    final response = await _dio.get('/v1/bank-accounts');
    return response.data;
  }

  Future<Map<String, dynamic>> getBankAccountById(String id) async {
    final response = await _dio.get('/v1/bank-accounts/$id');
    return response.data;
  }

  Future<List<dynamic>> getBankAccountsByProperty(String propertyId) async {
    final response = await _dio.get('/v1/bank-accounts/by-property/$propertyId');
    return response.data;
  }
}
